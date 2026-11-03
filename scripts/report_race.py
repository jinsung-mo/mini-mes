"""실적 보고 동시성 재현 스크립트.

여러 단말·설비가 같은 순간에 실적을 보고하는 상황을 만들어서, 실적 보고 API가 데이터를 맞게 남기는지 확인한다.
같은 요청을 C# 레거시와 Java에 보내서 결과를 비교할 수 있다. 표준 라이브러리만 쓴다.

  python scripts/report_race.py                      # 두 WAS, 두 시나리오
  python scripts/report_race.py --was java --repeat 5

시나리오
  lot       작업지시 4개 × 10건을 동시에 보고. 같은 공장·같은 근무일자라 LOT 번호를 같은 범위에서 딴다.
            기대: 40건 모두 성공, LOT 번호 40개 모두 다름
  complete  계획 수량 10인 작업지시 하나에 양품 1개씩 30건을 동시에 보고.
            기대: 10건 성공(10번째에 자동 완료), 나머지 20건은 E006(진행 중이 아님), 최종 양품 10
            LOT 채번 충돌이 섞이지 않게 요청마다 근무일자를 다르게 해서 LOT 번호 범위를 나눈다.
"""
import argparse
import collections
import datetime
import json
import sys
import threading
import time
import urllib.error
import urllib.request

WAS = {"legacy": "http://localhost:5080", "java": "http://localhost:8081"}

PLANT, ITEM, PROC, EQP = "KR01", "SEDAN-A", "BODY", "EQ-BODY-01"


def call(base, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            return json.loads(res.read())
    except urllib.error.HTTPError as e:
        return {"resultCode": f"HTTP{e.code}", "message": e.reason, "data": None}
    except OSError as e:
        return {"resultCode": "NET", "message": str(e), "data": None}


def ok(base, method, path, body=None):
    res = call(base, method, path, body)
    if res["resultCode"] != "0000":
        sys.exit(f"{base}{path} 실패: {res}")
    return res["data"]


def started_work_order(base, plan_qty):
    """작업지시를 만들고 작업 시작 상태(IN_PROGRESS)까지 진행한다"""
    wo = ok(base, "POST", "/api/work-orders", {
        "plantCd": PLANT, "itemCd": ITEM, "planQty": plan_qty,
        "planDate": datetime.date.today().isoformat()})
    ok(base, "POST", f"/api/work-orders/{wo['wo_id']}/release")
    ok(base, "POST", f"/api/work-orders/{wo['wo_id']}/start")
    return wo["wo_id"]


def fire_together(base, bodies):
    """모든 요청을 스레드에 나눠 담고, 전부 준비되면 한꺼번에 보낸다"""
    barrier = threading.Barrier(len(bodies))
    results = [None] * len(bodies)

    def worker(i):
        barrier.wait()
        results[i] = call(base, "POST", "/api/production-results", bodies[i])

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(len(bodies))]
    started = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return results, (time.perf_counter() - started) * 1000


def report_body(wo_id, work_date=None):
    # 보고 시각을 고정해서 근무일자·근무조를 정한다. LOT 번호 범위는 공장·근무일자마다 따로다
    work_date = work_date or datetime.date.today()
    return {"woId": wo_id, "procCd": PROC, "eqpCd": EQP, "goodQty": 1, "defectQty": 0,
            "reportedAt": f"{work_date.isoformat()}T10:00:00"}


def summarize_codes(results):
    counts = collections.Counter(r["resultCode"] for r in results)
    samples = {}
    for r in results:
        samples.setdefault(r["resultCode"], r["message"])
    return counts, samples


def check_consistency(base, wo_id):
    """작업지시 누적 수량, LOT 합계, 실적 합계가 서로 맞는지 본다 (트랜잭션이 깨지면 여기서 어긋난다)"""
    wo = ok(base, "GET", f"/api/work-orders/{wo_id}")
    lots = ok(base, "GET", f"/api/work-orders/{wo_id}/lots")
    results = ok(base, "GET", f"/api/work-orders/{wo_id}/results")
    lot_sum = sum(l["qty"] for l in lots)
    result_sum = sum(r["good_qty"] for r in results)
    return wo, wo["good_qty"] == lot_sum == result_sum


def scenario_lot(base):
    wo_ids = [started_work_order(base, 1000) for _ in range(4)]
    bodies = [report_body(wo_id) for wo_id in wo_ids for _ in range(10)]
    results, elapsed = fire_together(base, bodies)

    counts, samples = summarize_codes(results)
    lot_nos = [r["data"]["lot_no"] for r in results if r["resultCode"] == "0000"]
    consistent = all(check_consistency(base, wo_id)[1] for wo_id in wo_ids)
    passed = counts["0000"] == len(bodies) and len(set(lot_nos)) == len(lot_nos) and consistent
    return {
        "요청": len(bodies), "성공": counts["0000"], "응답 코드": dict(counts),
        "LOT 중복": len(lot_nos) - len(set(lot_nos)), "수량 정합성": consistent,
        "소요 ms": round(elapsed), "오류 예시": {k: v for k, v in samples.items() if k != "0000"},
    }, passed


def scenario_complete(base):
    plan_qty = 10
    wo_id = started_work_order(base, plan_qty)
    # 요청마다 근무일자를 하루씩 다르게 해서 LOT 채번이 서로 부딪히지 않게 한다 (완료 판정만 본다)
    first_day = datetime.date(2000, 1, 1)
    bodies = [report_body(wo_id, first_day + datetime.timedelta(days=i)) for i in range(30)]
    results, elapsed = fire_together(base, bodies)

    counts, samples = summarize_codes(results)
    wo, consistent = check_consistency(base, wo_id)
    passed = (counts["0000"] == plan_qty and counts["E006"] == 30 - plan_qty
              and wo["good_qty"] == plan_qty and wo["status"] == "COMPLETED" and consistent)
    return {
        "요청": 30, "성공": counts["0000"], "응답 코드": dict(counts),
        "최종 양품/계획": f"{wo['good_qty']}/{plan_qty}", "상태": wo["status"],
        "수량 정합성": consistent, "소요 ms": round(elapsed),
        "오류 예시": {k: v for k, v in samples.items() if k not in ("0000", "E006")},
    }, passed


SCENARIOS = {"lot": scenario_lot, "complete": scenario_complete}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--was", choices=["legacy", "java", "both"], default="both")
    parser.add_argument("--scenario", choices=["lot", "complete", "all"], default="all")
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()

    targets = ["legacy", "java"] if args.was == "both" else [args.was]
    scenarios = list(SCENARIOS) if args.scenario == "all" else [args.scenario]
    all_passed = True
    for name in scenarios:
        for was in targets:
            for n in range(1, args.repeat + 1):
                summary, passed = SCENARIOS[name](WAS[was])
                all_passed &= passed
                mark = "통과" if passed else "실패"
                print(f"[{mark}] {name:8} {was:6} #{n}  " + json.dumps(summary, ensure_ascii=False))
    return 0 if all_passed else 1


if __name__ == "__main__":
    sys.exit(main())
