using Microsoft.AspNetCore.Mvc;
using Npgsql;

namespace LegacyMes.Controllers;

// 레거시 MES API (전환 대상).
// 실무 레거시에서 흔한 모양을 일부러 재현했다. 무엇이 문제인지는 legacy-csharp/README.md 참고.
[ApiController]
[Route("api")]
public class MesController : ControllerBase
{
    private const string WoColumns = @"
        wo_id, plant_cd, wo_no, item_cd, plan_qty, good_qty, defect_qty, status,
        to_char(plan_date, 'YYYY-MM-DD') AS plan_date,
        to_char(started_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS started_at,
        to_char(completed_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS completed_at";

    private readonly string _connStr;

    public MesController(IConfiguration config)
    {
        _connStr = config.GetConnectionString("Mes")!;
    }

    public class CreateWorkOrderRequest
    {
        public string? PlantCd { get; set; }
        public string? ItemCd { get; set; }
        public int PlanQty { get; set; }
        public string? PlanDate { get; set; }   // yyyy-MM-dd
    }

    public class ProductionResultRequest
    {
        public long WoId { get; set; }
        public string? ProcCd { get; set; }
        public string? EqpCd { get; set; }
        public int GoodQty { get; set; }
        public int DefectQty { get; set; }
        public string? DefectCd { get; set; }
        public string? ReportedAt { get; set; } // 단말이 보낸 시각. 없으면 서버 현재 시각
    }

    // GET api/work-orders?plantCd=KR01&status=IN_PROGRESS
    [HttpGet("work-orders")]
    public IActionResult GetWorkOrders(string? plantCd, string? status)
    {
        return Run(() =>
        {
            if (string.IsNullOrEmpty(plantCd)) return Fail("E001", "plantCd is required");

            using var conn = Open();
            var sql = "SELECT " + WoColumns + " FROM work_order WHERE plant_cd = @plant";
            if (!string.IsNullOrEmpty(status)) sql += " AND status = @status";
            sql += " ORDER BY wo_id";

            using var cmd = new NpgsqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("plant", plantCd);
            if (!string.IsNullOrEmpty(status)) cmd.Parameters.AddWithValue("status", status);
            return Success(ReadAll(cmd));
        });
    }

    // GET api/work-orders/1
    [HttpGet("work-orders/{id}")]
    public IActionResult GetWorkOrder(long id)
    {
        return Run(() =>
        {
            using var conn = Open();
            var wo = FindWorkOrder(conn, id);
            if (wo == null) return Fail("E004", "work order not found");
            return Success(wo);
        });
    }

    // POST api/work-orders
    [HttpPost("work-orders")]
    public IActionResult CreateWorkOrder([FromBody] CreateWorkOrderRequest req)
    {
        return Run(() =>
        {
            if (string.IsNullOrEmpty(req.PlantCd) || string.IsNullOrEmpty(req.ItemCd))
                return Fail("E001", "plantCd and itemCd are required");
            if (!DateTime.TryParseExact(req.PlanDate, "yyyy-MM-dd", null,
                    System.Globalization.DateTimeStyles.None, out var planDate))
                return Fail("E001", "planDate must be yyyy-MM-dd");
            if (req.PlanQty <= 0) return Fail("E002", "planQty must be positive");

            using var conn = Open();

            using (var chk = new NpgsqlCommand(
                       "SELECT COUNT(*) FROM item WHERE plant_cd = @plant AND item_cd = @item", conn))
            {
                chk.Parameters.AddWithValue("plant", req.PlantCd);
                chk.Parameters.AddWithValue("item", req.ItemCd);
                if ((long)chk.ExecuteScalar()! == 0) return Fail("E003", "item not found");
            }

            // 채번: WO-{공장}-{계획일}-{순번3}
            var prefix = "WO-" + req.PlantCd + "-" + planDate.ToString("yyyyMMdd") + "-";
            int seq;
            using (var seqCmd = new NpgsqlCommand(
                       "SELECT COALESCE(MAX(CAST(RIGHT(wo_no, 3) AS INT)), 0) + 1 FROM work_order WHERE wo_no LIKE @prefix || '%'", conn))
            {
                seqCmd.Parameters.AddWithValue("prefix", prefix);
                seq = (int)seqCmd.ExecuteScalar()!;
            }
            var woNo = prefix + seq.ToString("D3");

            using var cmd = new NpgsqlCommand(@"
                INSERT INTO work_order (plant_cd, wo_no, item_cd, plan_qty, status, plan_date)
                VALUES (@plant, @woNo, @item, @qty, 'CREATED', @planDate::date)
                RETURNING wo_id", conn);
            cmd.Parameters.AddWithValue("plant", req.PlantCd);
            cmd.Parameters.AddWithValue("woNo", woNo);
            cmd.Parameters.AddWithValue("item", req.ItemCd);
            cmd.Parameters.AddWithValue("qty", req.PlanQty);
            cmd.Parameters.AddWithValue("planDate", req.PlanDate!);
            var woId = (long)cmd.ExecuteScalar()!;

            return Success(new Dictionary<string, object?> { ["wo_id"] = woId, ["wo_no"] = woNo });
        });
    }

    [HttpPost("work-orders/{id}/release")]
    public IActionResult Release(long id) => Run(() => ChangeStatus(id, "CREATED", "RELEASED", null));

    [HttpPost("work-orders/{id}/start")]
    public IActionResult Start(long id) => Run(() => ChangeStatus(id, "RELEASED", "IN_PROGRESS", "started_at"));

    [HttpPost("work-orders/{id}/complete")]
    public IActionResult Complete(long id) => Run(() => ChangeStatus(id, "IN_PROGRESS", "COMPLETED", "completed_at"));

    // POST api/production-results  (단말/설비에서 실적 보고)
    [HttpPost("production-results")]
    public IActionResult ReportResult([FromBody] ProductionResultRequest req)
    {
        return Run(() =>
        {
            if (req.WoId <= 0 || string.IsNullOrEmpty(req.ProcCd) || string.IsNullOrEmpty(req.EqpCd))
                return Fail("E001", "woId, procCd, eqpCd are required");
            if (req.GoodQty < 0 || req.DefectQty < 0 || req.GoodQty + req.DefectQty == 0)
                return Fail("E002", "invalid quantity");

            DateTime reportedAt;
            if (string.IsNullOrEmpty(req.ReportedAt)) reportedAt = DateTime.Now;
            else if (!DateTime.TryParse(req.ReportedAt, out reportedAt))
                return Fail("E001", "reportedAt is invalid");

            using var conn = Open();
            using var tx = conn.BeginTransaction();

            string plantCd, itemCd, status;
            int planQty;
            using (var woCmd = new NpgsqlCommand(
                       "SELECT plant_cd, item_cd, plan_qty, status FROM work_order WHERE wo_id = @id", conn, tx))
            {
                woCmd.Parameters.AddWithValue("id", req.WoId);
                using var rd = woCmd.ExecuteReader();
                if (!rd.Read()) return Fail("E004", "work order not found");
                plantCd = rd.GetString(0);
                itemCd = rd.GetString(1);
                planQty = rd.GetInt32(2);
                status = rd.GetString(3);
            }
            if (status != "IN_PROGRESS") return Fail("E006", "work order is not in progress");

            using (var eqCmd = new NpgsqlCommand(
                       "SELECT COUNT(*) FROM equipment WHERE plant_cd = @plant AND eqp_cd = @eqp AND proc_cd = @proc", conn, tx))
            {
                eqCmd.Parameters.AddWithValue("plant", plantCd);
                eqCmd.Parameters.AddWithValue("eqp", req.EqpCd);
                eqCmd.Parameters.AddWithValue("proc", req.ProcCd);
                if ((long)eqCmd.ExecuteScalar()! == 0) return Fail("E009", "equipment not found");
            }

            if (req.DefectQty > 0 && string.IsNullOrEmpty(req.DefectCd))
                return Fail("E007", "defectCd is required when defectQty > 0");
            if (!string.IsNullOrEmpty(req.DefectCd))
            {
                using var dcCmd = new NpgsqlCommand("SELECT COUNT(*) FROM defect_code WHERE defect_cd = @cd", conn, tx);
                dcCmd.Parameters.AddWithValue("cd", req.DefectCd);
                if ((long)dcCmd.ExecuteScalar()! == 0) return Fail("E008", "defect code not found");
            }

            // 근무조: A 06~14, B 14~22, C 22~06 (00~06시는 전날 근무일자)
            var h = reportedAt.Hour;
            var workDate = reportedAt.Date;
            string shiftCd;
            if (h >= 6 && h < 14) shiftCd = "A";
            else if (h >= 14 && h < 22) shiftCd = "B";
            else
            {
                shiftCd = "C";
                if (h < 6) workDate = workDate.AddDays(-1);
            }

            string? lotNo = null;
            if (req.GoodQty > 0)
            {
                // 채번: L-{공장}-{근무일자}-{순번4}
                var prefix = "L-" + plantCd + "-" + workDate.ToString("yyMMdd") + "-";
                int seq;
                using (var seqCmd = new NpgsqlCommand(
                           "SELECT COALESCE(MAX(CAST(RIGHT(lot_no, 4) AS INT)), 0) + 1 FROM lot WHERE lot_no LIKE @prefix || '%'", conn, tx))
                {
                    seqCmd.Parameters.AddWithValue("prefix", prefix);
                    seq = (int)seqCmd.ExecuteScalar()!;
                }
                lotNo = prefix + seq.ToString("D4");

                using var lotCmd = new NpgsqlCommand(
                    "INSERT INTO lot (lot_no, plant_cd, wo_id, item_cd, qty) VALUES (@lot, @plant, @wo, @item, @qty)", conn, tx);
                lotCmd.Parameters.AddWithValue("lot", lotNo);
                lotCmd.Parameters.AddWithValue("plant", plantCd);
                lotCmd.Parameters.AddWithValue("wo", req.WoId);
                lotCmd.Parameters.AddWithValue("item", itemCd);
                lotCmd.Parameters.AddWithValue("qty", req.GoodQty);
                lotCmd.ExecuteNonQuery();
            }

            long resultId;
            using (var resCmd = new NpgsqlCommand(@"
                       INSERT INTO production_result
                           (plant_cd, wo_id, proc_cd, eqp_cd, lot_no, good_qty, defect_qty, defect_cd, work_date, shift_cd, reported_at)
                       VALUES (@plant, @wo, @proc, @eqp, @lot, @good, @defect, @dcd, @wd::date, @shift, @at)
                       RETURNING result_id", conn, tx))
            {
                resCmd.Parameters.AddWithValue("plant", plantCd);
                resCmd.Parameters.AddWithValue("wo", req.WoId);
                resCmd.Parameters.AddWithValue("proc", req.ProcCd);
                resCmd.Parameters.AddWithValue("eqp", req.EqpCd);
                resCmd.Parameters.AddWithValue("lot", (object?)lotNo ?? DBNull.Value);
                resCmd.Parameters.AddWithValue("good", req.GoodQty);
                resCmd.Parameters.AddWithValue("defect", req.DefectQty);
                resCmd.Parameters.AddWithValue("dcd", string.IsNullOrEmpty(req.DefectCd) ? DBNull.Value : req.DefectCd);
                resCmd.Parameters.AddWithValue("wd", workDate.ToString("yyyy-MM-dd"));
                resCmd.Parameters.AddWithValue("shift", shiftCd);
                resCmd.Parameters.AddWithValue("at", reportedAt.ToUniversalTime());
                resultId = (long)resCmd.ExecuteScalar()!;
            }

            int totalGood;
            using (var upCmd = new NpgsqlCommand(@"
                       UPDATE work_order
                          SET good_qty = good_qty + @good, defect_qty = defect_qty + @defect, updated_at = now()
                        WHERE wo_id = @wo
                       RETURNING good_qty", conn, tx))
            {
                upCmd.Parameters.AddWithValue("good", req.GoodQty);
                upCmd.Parameters.AddWithValue("defect", req.DefectQty);
                upCmd.Parameters.AddWithValue("wo", req.WoId);
                totalGood = (int)upCmd.ExecuteScalar()!;
            }

            // 계획 수량을 채우면 자동 완료
            var woStatus = status;
            if (totalGood >= planQty)
            {
                using var doneCmd = new NpgsqlCommand(
                    "UPDATE work_order SET status = 'COMPLETED', completed_at = now(), updated_at = now() WHERE wo_id = @wo", conn, tx);
                doneCmd.Parameters.AddWithValue("wo", req.WoId);
                doneCmd.ExecuteNonQuery();
                woStatus = "COMPLETED";
            }

            tx.Commit();

            return Success(new Dictionary<string, object?>
            {
                ["result_id"] = resultId,
                ["lot_no"] = lotNo,
                ["work_date"] = workDate.ToString("yyyy-MM-dd"),
                ["shift_cd"] = shiftCd,
                ["wo_good_qty"] = totalGood,
                ["wo_status"] = woStatus
            });
        });
    }

    // GET api/work-orders/1/lots
    [HttpGet("work-orders/{id}/lots")]
    public IActionResult GetLots(long id)
    {
        return Run(() =>
        {
            using var conn = Open();
            using var cmd = new NpgsqlCommand(@"
                SELECT lot_no, item_cd, qty,
                       to_char(created_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS created_at
                  FROM lot WHERE wo_id = @id ORDER BY lot_no", conn);
            cmd.Parameters.AddWithValue("id", id);
            return Success(ReadAll(cmd));
        });
    }

    // GET api/work-orders/1/results
    [HttpGet("work-orders/{id}/results")]
    public IActionResult GetResults(long id)
    {
        return Run(() =>
        {
            using var conn = Open();
            using var cmd = new NpgsqlCommand(@"
                SELECT result_id, proc_cd, eqp_cd, lot_no, good_qty, defect_qty, defect_cd,
                       to_char(work_date, 'YYYY-MM-DD') AS work_date, shift_cd,
                       to_char(reported_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') AS reported_at
                  FROM production_result WHERE wo_id = @id ORDER BY result_id", conn);
            cmd.Parameters.AddWithValue("id", id);
            return Success(ReadAll(cmd));
        });
    }

    // ---- 기준정보 조회 (단말 화면의 콤보박스용) ----

    // GET api/plants
    [HttpGet("plants")]
    public IActionResult GetPlants()
    {
        return Run(() =>
        {
            using var conn = Open();
            using var cmd = new NpgsqlCommand("SELECT plant_cd, plant_nm, time_zone FROM plant ORDER BY plant_cd", conn);
            return Success(ReadAll(cmd));
        });
    }

    // GET api/items?plantCd=KR01
    [HttpGet("items")]
    public IActionResult GetItems(string? plantCd)
    {
        return Run(() =>
        {
            if (string.IsNullOrEmpty(plantCd)) return Fail("E001", "plantCd is required");

            using var conn = Open();
            using var cmd = new NpgsqlCommand(
                "SELECT item_cd, item_nm FROM item WHERE plant_cd = @plant ORDER BY item_cd", conn);
            cmd.Parameters.AddWithValue("plant", plantCd);
            return Success(ReadAll(cmd));
        });
    }

    // GET api/routings?plantCd=KR01&itemCd=SEDAN-A
    [HttpGet("routings")]
    public IActionResult GetRoutings(string? plantCd, string? itemCd)
    {
        return Run(() =>
        {
            if (string.IsNullOrEmpty(plantCd) || string.IsNullOrEmpty(itemCd))
                return Fail("E001", "plantCd and itemCd are required");

            using var conn = Open();
            using var cmd = new NpgsqlCommand(@"
                SELECT r.seq, r.proc_cd, p.proc_nm
                  FROM routing r
                  JOIN process p ON p.plant_cd = r.plant_cd AND p.proc_cd = r.proc_cd
                 WHERE r.plant_cd = @plant AND r.item_cd = @item
                 ORDER BY r.seq", conn);
            cmd.Parameters.AddWithValue("plant", plantCd);
            cmd.Parameters.AddWithValue("item", itemCd);
            return Success(ReadAll(cmd));
        });
    }

    // GET api/equipments?plantCd=KR01
    [HttpGet("equipments")]
    public IActionResult GetEquipments(string? plantCd)
    {
        return Run(() =>
        {
            if (string.IsNullOrEmpty(plantCd)) return Fail("E001", "plantCd is required");

            using var conn = Open();
            using var cmd = new NpgsqlCommand(
                "SELECT eqp_cd, eqp_nm, proc_cd FROM equipment WHERE plant_cd = @plant ORDER BY eqp_cd", conn);
            cmd.Parameters.AddWithValue("plant", plantCd);
            return Success(ReadAll(cmd));
        });
    }

    // GET api/defect-codes
    [HttpGet("defect-codes")]
    public IActionResult GetDefectCodes()
    {
        return Run(() =>
        {
            using var conn = Open();
            using var cmd = new NpgsqlCommand("SELECT defect_cd, defect_nm FROM defect_code ORDER BY defect_cd", conn);
            return Success(ReadAll(cmd));
        });
    }

    private IActionResult ChangeStatus(long id, string from, string to, string? timeColumn)
    {
        using var conn = Open();

        using (var cmd = new NpgsqlCommand("SELECT status FROM work_order WHERE wo_id = @id", conn))
        {
            cmd.Parameters.AddWithValue("id", id);
            var current = (string?)cmd.ExecuteScalar();
            if (current == null) return Fail("E004", "work order not found");
            if (current != from) return Fail("E005", "invalid status transition: " + current + " -> " + to);
        }

        var sql = "UPDATE work_order SET status = @to, updated_at = now()";
        if (timeColumn != null) sql += ", " + timeColumn + " = now()";
        sql += " WHERE wo_id = @id";
        using (var cmd = new NpgsqlCommand(sql, conn))
        {
            cmd.Parameters.AddWithValue("to", to);
            cmd.Parameters.AddWithValue("id", id);
            cmd.ExecuteNonQuery();
        }

        return Success(FindWorkOrder(conn, id));
    }

    private Dictionary<string, object?>? FindWorkOrder(NpgsqlConnection conn, long id)
    {
        using var cmd = new NpgsqlCommand("SELECT " + WoColumns + " FROM work_order WHERE wo_id = @id", conn);
        cmd.Parameters.AddWithValue("id", id);
        return ReadAll(cmd).FirstOrDefault();
    }

    private NpgsqlConnection Open()
    {
        var conn = new NpgsqlConnection(_connStr);
        conn.Open();
        return conn;
    }

    private static List<Dictionary<string, object?>> ReadAll(NpgsqlCommand cmd)
    {
        var rows = new List<Dictionary<string, object?>>();
        using var rd = cmd.ExecuteReader();
        while (rd.Read())
        {
            var row = new Dictionary<string, object?>();
            for (var i = 0; i < rd.FieldCount; i++)
                row[rd.GetName(i)] = rd.IsDBNull(i) ? null : rd.GetValue(i);
            rows.Add(row);
        }
        return rows;
    }

    // 모든 응답은 HTTP 200 + resultCode. 클라이언트(단말 화면)가 이 형식에 맞춰 만들어져 있다.
    private IActionResult Success(object? data) =>
        Ok(new { resultCode = "0000", message = "OK", data });

    private IActionResult Fail(string code, string message) =>
        Ok(new { resultCode = code, message, data = (object?)null });

    private IActionResult Run(Func<IActionResult> action)
    {
        try
        {
            return action();
        }
        catch (Exception ex)
        {
            return Fail("E999", ex.Message);
        }
    }
}
