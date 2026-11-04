"""동등성 검증 시나리오와 의도한 차이.

equivalence.py가 아래 순서대로 C# 레거시와 Java에 같은 요청을 보내고 결과를 비교한다.

- 요청마다 C# 레거시가 돌려줘야 하는 결과 코드(expect)를 함께 적는다. 레거시가 지금 어떻게 동작하는지 글로 남긴
  특성 테스트(characterization test)이기도 하다. C#이 이 값과 다르게 답하면 Java와 비교하기 전에 시나리오부터 다시 본다.
- 날짜를 고정해서 언제 실행해도 같은 작업지시 번호와 LOT 번호가 나온다.
- 요청 해석 방식만 보려는 요청은 해석에 성공해도 뒤쪽 검증(품목 없음 등)에서 걸리게 만든다.
  한쪽만 성공하면 두 DB가 갈라져서 그 뒤의 비교가 줄줄이 어긋나기 때문이다.
"""
from dataclasses import dataclass

DAY = "2026-11-03"
NEXT_DAY = "2026-11-04"
WORK_ORDERS = "/api/work-orders"
RESULTS = "/api/production-results"


@dataclass(frozen=True)
class Var:
    """앞 요청의 응답에서 저장해 둔 값. 요청을 보낼 때 실제 값(숫자는 숫자 그대로)으로 바뀐다"""
    name: str


OMIT = object()  # 요청 본문에서 이 키를 뺀다


@dataclass
class Step:
    section: str
    name: str
    method: str
    path: str                     # $wo_flow처럼 저장해 둔 값을 넣을 수 있다
    body: object = None           # JSON으로 바꿔 보낼 값
    raw: str | None = None        # JSON으로 바꾸지 않고 그대로 보낼 본문 (깨진 JSON 등)
    content_type: str | None = "application/json"
    expect: str = "0000"          # C# 레거시의 결과. resultCode, resultCode가 없는 응답이면 "HTTP 400"처럼 상태 코드
    save: dict | None = None      # {"이름": "data.wo_id"}: 응답에서 꺼내 둘 값
    known: tuple | None = None    # (의도한 차이 ID, Java의 결과): 레거시와 일부러 다르게 만든 요청


@dataclass(frozen=True)
class KnownDifference:
    title: str
    legacy: str
    java: str
    reason: str
    doc: str


# 레거시와 일부러 다르게 만든 동작. 여기에 이유와 함께 등록한 차이만 통과시키고, 나머지 차이는 모두 전환 실수로 본다.
KNOWN_DIFFERENCES = {
    "E999-message": KnownDifference(
        title="E999 응답 메시지",
        legacy="예외 메시지를 그대로 응답 (DB 오류 내용 포함)",
        java="참조 번호만 응답하고, 예외 내용은 서버 로그에 남김",
        reason="오류 응답에 서버 내부 정보가 보이던 문제를 고쳤다. resultCode와 HTTP 200은 같아서 응답 계약은 그대로",
        doc="docs/troubleshooting.md#3-오류-응답에-서버-경로와-sql-노출",
    ),
    "reported-at-format": KnownDifference(
        title="보고 시각 형식",
        legacy="DateTime.TryParse가 받는 형식을 모두 받음 (예: 2026/11/03 08:00)",
        java="ISO 형식과 그 변형만 받고, 나머지는 E001",
        reason="단말 화면은 ISO 형식만 보낸다. TryParse는 서버 문화권 설정에 따라 해석이 달라져서 그대로 따라 하지 않았다",
        doc="mes-api/README.md#실적-보고-기능-경계를-넘는-요청",
    ),
    "lot-seq-table": KnownDifference(
        title="LOT 채번 테이블 (lot_seq)",
        legacy="쓰지 않음. LOT 테이블의 MAX + 1로 채번",
        java="공장·근무일자별 마지막 순번을 남김",
        reason="동시 보고 때 LOT 번호가 겹치던 문제를 고쳤다. 발행되는 LOT 번호는 같다",
        doc="docs/troubleshooting.md#1-동시-보고-시-lot-번호-중복",
    ),
    "head-options": KnownDifference(
        title="HEAD·OPTIONS 요청",
        legacy="405. GET 주소에 HEAD·OPTIONS를 받지 않음",
        java="200. Spring MVC가 GET 주소의 HEAD(본문 없는 GET)와 OPTIONS(허용 메서드 안내)를 알아서 처리",
        reason="데이터를 읽기만 하는 HTTP 표준 동작이다. 레거시에서는 늘 실패하던 요청이라 여기에 기대는 클라이언트가 없다",
        doc="docs/03-equivalence.md#의도한-차이로-둔-것",
    ),
}


def check_lot_seq(legacy_rows, java_rows, java_db):
    """lot_seq 차이가 의도한 모양인지 본다. C#은 이 테이블을 쓰지 않고, Java 값은 LOT 번호의 마지막 순번과 같아야 한다.
    맞으면 None, 아니면 무엇이 다른지 돌려준다."""
    if legacy_rows:
        return f"C# DB에 lot_seq 행이 {len(legacy_rows)}개 있음 (레거시는 이 테이블을 쓰지 않음)"
    expected = {}
    for lot in java_db["lot"]:
        _, plant, yymmdd, seq = lot["lot_no"].split("-")  # L-KR01-261103-0001
        work_date = f"20{yymmdd[:2]}-{yymmdd[2:4]}-{yymmdd[4:]}"
        expected[(plant, work_date)] = max(expected.get((plant, work_date), 0), int(seq))
    actual = {(row["plant_cd"], row["work_date"]): row["last_seq"] for row in java_rows}
    if actual != expected:
        return f"Java lot_seq {actual}가 LOT 번호의 마지막 순번 {expected}와 다름"
    return None


# 테이블 단위 의도한 차이: 테이블 이름 → (의도한 차이 ID, 차이가 의도한 모양인지 보는 함수)
KNOWN_TABLES = {"lot_seq": ("lot-seq-table", check_lot_seq)}


def work_order(**fields):
    return _with({"plantCd": "KR01", "itemCd": "SEDAN-A", "planQty": 1, "planDate": DAY}, fields)


def report(wo, **fields):
    """보고 시각(reportedAt)을 주지 않으면 서버 현재 시각으로 처리된다"""
    return _with({"woId": wo, "procCd": "BODY", "eqpCd": "EQ-BODY-01", "goodQty": 1, "defectQty": 0}, fields)


def _with(body, fields):
    body = dict(body)
    for key, value in fields.items():
        if value is OMIT:
            body.pop(key, None)
        else:
            body[key] = value
    return body


class Scenario:
    def __init__(self):
        self.steps = []
        self._section = ""

    def section(self, name):
        self._section = name

    def get(self, name, path, expect="0000", **options):
        self.call("GET", name, path, expect, **options)

    def post(self, name, path, body=None, expect="0000", **options):
        self.call("POST", name, path, expect, body=body, **options)

    def call(self, method, name, path, expect="0000", **options):
        self.steps.append(Step(self._section, name, method, path, expect=expect, **options))


def steps():
    s = Scenario()

    s.section("기준정보 조회")
    s.get("공장 목록", "/api/plants")
    s.get("품목: 울산", "/api/items?plantCd=KR01")
    s.get("품목: 체코", "/api/items?plantCd=CZ01")
    s.get("품목: 없는 공장이면 빈 목록", "/api/items?plantCd=XX99")
    s.get("품목: plantCd 없음", "/api/items", "E001")
    s.get("품목: plantCd 빈 값", "/api/items?plantCd=", "E001")
    s.get("라우팅: 울산 SUV", "/api/routings?plantCd=KR01&itemCd=SUV-B")
    s.get("라우팅: 없는 품목이면 빈 목록", "/api/routings?plantCd=KR01&itemCd=NOPE")
    s.get("라우팅: itemCd 없음", "/api/routings?plantCd=KR01", "E001")
    s.get("라우팅: plantCd 없음", "/api/routings?itemCd=SEDAN-A", "E001")
    s.get("설비: 앨라배마", "/api/equipments?plantCd=US01")
    s.get("설비: plantCd 없음", "/api/equipments", "E001")
    s.get("불량 코드", "/api/defect-codes")

    s.section("작업지시 생성")
    s.post("울산 세단, 계획 10", WORK_ORDERS, work_order(planQty=10), save={"wo_flow": "data.wo_id"})
    s.post("같은 공장·계획일이면 순번 002", WORK_ORDERS, work_order(itemCd="SUV-B", planQty=5),
           save={"wo_suv": "data.wo_id"})
    s.post("계획일이 다르면 순번 001", WORK_ORDERS, work_order(planQty=2, planDate=NEXT_DAY),
           save={"wo_manual": "data.wo_id"})
    s.post("공장이 다르면 순번 001 (앨라배마)", WORK_ORDERS, work_order(plantCd="US01", planQty=3),
           save={"wo_us": "data.wo_id"})
    s.post("계획 수량 int 최댓값", WORK_ORDERS, work_order(planQty=2147483647), save={"wo_big": "data.wo_id"})
    s.post("키 대소문자가 달라도 받음", WORK_ORDERS,
           {"PlantCd": "KR01", "ItemCd": "SUV-B", "PlanQty": 1, "PlanDate": DAY}, save={"wo_created": "data.wo_id"})
    s.post("plantCd 없음", WORK_ORDERS, work_order(plantCd=OMIT), "E001")
    s.post("itemCd 빈 값", WORK_ORDERS, work_order(itemCd=""), "E001")
    s.post("planDate 없음", WORK_ORDERS, work_order(planDate=OMIT), "E001")
    s.post("planDate 없는 날짜 2026-02-30", WORK_ORDERS, work_order(planDate="2026-02-30"), "E001")
    s.post("planDate 한 자리 월 2026-1-5", WORK_ORDERS, work_order(planDate="2026-1-5"), "E001")
    s.post("planDate 2026/11/03", WORK_ORDERS, work_order(planDate="2026/11/03"), "E001")
    s.post("planDate 시각까지 있음", WORK_ORDERS, work_order(planDate=f"{DAY}T00:00:00"), "E001")
    s.post("planQty 0", WORK_ORDERS, work_order(planQty=0), "E002")
    s.post("planQty 음수", WORK_ORDERS, work_order(planQty=-5), "E002")
    s.post("planQty 키 없음", WORK_ORDERS, work_order(planQty=OMIT), "E002")
    s.post("없는 품목", WORK_ORDERS, work_order(itemCd="NOPE"), "E003")
    s.post("없는 공장", WORK_ORDERS, work_order(plantCd="XX99"), "E003")
    s.post("순서: 필수값 + 수량 → E001", WORK_ORDERS, work_order(plantCd=OMIT, planQty=0), "E001")
    s.post("순서: 계획일 + 수량 → E001", WORK_ORDERS, work_order(planDate="x", planQty=0), "E001")
    s.post("순서: 수량 + 품목 → E002", WORK_ORDERS, work_order(itemCd="NOPE", planQty=0), "E002")

    # 해석에 성공해도 품목 확인(E003)에서 걸리게 해서, 어느 쪽도 작업지시를 만들지 않게 한다
    s.section("요청 JSON 해석")
    no_item = work_order(itemCd="NOPE")
    s.post("planQty 소수 10.5", WORK_ORDERS, {**no_item, "planQty": 10.5}, "HTTP 400")
    s.post("planQty 10.0", WORK_ORDERS, {**no_item, "planQty": 10.0}, "HTTP 400")
    s.post("planQty null", WORK_ORDERS, {**no_item, "planQty": None}, "HTTP 400")
    s.post("planQty int 범위 밖", WORK_ORDERS, {**no_item, "planQty": 2147483648}, "HTTP 400")
    s.post("planQty 문자열 \"4\"", WORK_ORDERS, {**no_item, "planQty": "4"}, "E003")
    s.post("planQty 문자열 \"abc\"", WORK_ORDERS, {**no_item, "planQty": "abc"}, "HTTP 400")
    s.post("plantCd에 숫자", WORK_ORDERS, {**no_item, "plantCd": 1}, "HTTP 400")
    s.post("plantCd에 소수", WORK_ORDERS, {**no_item, "plantCd": 1.5}, "HTTP 400")
    s.post("itemCd에 true", WORK_ORDERS, {**no_item, "itemCd": True}, "HTTP 400")
    s.post("plantCd에 객체", WORK_ORDERS, {**no_item, "plantCd": {"code": "KR01"}}, "HTTP 400")
    s.post("planQty에 true", WORK_ORDERS, {**no_item, "planQty": True}, "HTTP 400")
    s.post("모르는 키가 섞임", WORK_ORDERS, {**no_item, "memo": "야간 특근"}, "E003")
    s.post("깨진 JSON", WORK_ORDERS, raw='{"plantCd": "KR01", ', expect="HTTP 400")
    s.post("JSON 배열", WORK_ORDERS, raw="[]", expect="HTTP 400")
    s.post("JSON null", WORK_ORDERS, raw="null", expect="HTTP 400")
    s.post("빈 본문", WORK_ORDERS, raw="", expect="HTTP 400")
    s.post("본문과 Content-Type 없음", WORK_ORDERS, expect="HTTP 415")
    s.post("본문은 있는데 Content-Type 없음", WORK_ORDERS, raw='{"plantCd": "KR01"}', content_type=None,
           expect="HTTP 415")
    s.post("Content-Type text/plain", WORK_ORDERS, raw='{"plantCd": "KR01"}', content_type="text/plain",
           expect="HTTP 415")

    s.section("작업지시 조회")
    flow = "/api/work-orders/$wo_flow"
    s.get("목록: 울산", "/api/work-orders?plantCd=KR01")
    s.get("목록: 울산 CREATED", "/api/work-orders?plantCd=KR01&status=CREATED")
    s.get("목록: 없는 상태면 빈 목록", "/api/work-orders?plantCd=KR01&status=FOO")
    s.get("목록: 앨라배마", "/api/work-orders?plantCd=US01")
    s.get("목록: plantCd 없음", "/api/work-orders", "E001")
    s.get("목록: plantCd 빈 값", "/api/work-orders?plantCd=&status=CREATED", "E001")
    s.get("상세", flow)
    s.get("상세: 없는 작업지시", "/api/work-orders/99999999", "E004")
    s.get("상세: id 0", "/api/work-orders/0", "E004")
    s.get("상세: id 음수", "/api/work-orders/-1", "E004")
    s.get("상세: 숫자가 아닌 id", "/api/work-orders/abc", "HTTP 400")
    s.get("LOT: 없는 작업지시면 빈 목록", "/api/work-orders/99999999/lots")
    s.get("실적: 없는 작업지시면 빈 목록", "/api/work-orders/99999999/results")

    s.section("상태 전이")
    s.post("릴리즈", f"{flow}/release")
    s.post("릴리즈 두 번", f"{flow}/release", expect="E005")
    s.post("릴리즈 상태에서 완료", f"{flow}/complete", expect="E005")
    s.post("시작", f"{flow}/start")
    s.post("시작 두 번", f"{flow}/start", expect="E005")
    s.post("진행 중에 릴리즈", f"{flow}/release", expect="E005")
    s.post("생성 상태에서 시작", "/api/work-orders/$wo_suv/start", expect="E005")
    s.post("생성 상태에서 완료", "/api/work-orders/$wo_suv/complete", expect="E005")
    s.post("없는 작업지시 릴리즈", "/api/work-orders/99999999/release", expect="E004")
    s.post("숫자가 아닌 id 시작", "/api/work-orders/abc/start", expect="HTTP 400")
    s.get("GET으로 릴리즈", "/api/work-orders/$wo_suv/release", "HTTP 405")
    manual = "/api/work-orders/$wo_manual"
    s.post("수동 완료할 작업지시 릴리즈", f"{manual}/release")
    s.post("수동 완료할 작업지시 시작", f"{manual}/start")
    s.post("수동 완료", f"{manual}/complete")
    s.post("완료 두 번", f"{manual}/complete", expect="E005")
    s.post("완료 후 릴리즈", f"{manual}/release", expect="E005")
    for var, label in (("wo_suv", "SUV"), ("wo_us", "앨라배마"), ("wo_big", "계획 최댓값")):
        s.post(f"실적 보고 준비: {label} 릴리즈", f"/api/work-orders/${var}/release")
        s.post(f"실적 보고 준비: {label} 시작", f"/api/work-orders/${var}/start")

    s.section("실적 보고: 검증")

    def flow_report(**fields):
        return report(Var("wo_flow"), **fields)

    s.post("woId 없음", RESULTS, flow_report(woId=OMIT), "E001")
    s.post("woId 0", RESULTS, flow_report(woId=0), "E001")
    s.post("woId 음수", RESULTS, flow_report(woId=-1), "E001")
    s.post("procCd 없음", RESULTS, flow_report(procCd=OMIT), "E001")
    s.post("eqpCd 빈 값", RESULTS, flow_report(eqpCd=""), "E001")
    s.post("양품 음수", RESULTS, flow_report(goodQty=-1), "E002")
    s.post("불량 음수", RESULTS, flow_report(defectQty=-1), "E002")
    s.post("양품·불량 모두 0", RESULTS, flow_report(goodQty=0), "E002")
    s.post("수량 키 없음", RESULTS, flow_report(goodQty=OMIT, defectQty=OMIT), "E002")
    s.post("보고 시각 형식 오류", RESULTS, flow_report(reportedAt="abc"), "E001")
    s.post("보고 시각 없는 날짜", RESULTS, flow_report(reportedAt="2026-02-30T08:00:00"), "E001")
    s.post("없는 작업지시", RESULTS, flow_report(woId=99999999), "E004")
    s.post("생성 상태 작업지시", RESULTS, report(Var("wo_created")), "E006")
    s.post("완료된 작업지시", RESULTS, report(Var("wo_manual")), "E006")
    s.post("공정과 설비가 맞지 않음", RESULTS, flow_report(eqpCd="EQ-PAINT-01"), "E009")
    s.post("없는 설비", RESULTS, flow_report(eqpCd="EQ-NOPE-01"), "E009")
    s.post("불량이 있는데 불량 코드 없음", RESULTS, flow_report(goodQty=0, defectQty=1), "E007")
    s.post("없는 불량 코드", RESULTS, flow_report(goodQty=0, defectQty=1, defectCd="ZZZ"), "E008")
    s.post("불량 0인데 없는 불량 코드", RESULTS, flow_report(defectCd="ZZZ"), "E008")
    s.post("순서: 필수값 + 수량 → E001", RESULTS, flow_report(procCd=OMIT, goodQty=-1), "E001")
    s.post("순서: 수량 + 보고 시각 → E002", RESULTS, flow_report(goodQty=-1, reportedAt="abc"), "E002")
    s.post("순서: 보고 시각 + 작업지시 → E001", RESULTS, flow_report(woId=99999999, reportedAt="abc"), "E001")
    s.post("순서: 작업지시 상태 + 설비 → E006", RESULTS, report(Var("wo_created"), eqpCd="EQ-PAINT-01"), "E006")
    s.post("순서: 설비 + 불량 코드 → E009", RESULTS, flow_report(eqpCd="EQ-PAINT-01", goodQty=0, defectQty=1),
           "E009")
    s.post("양품 소수", RESULTS, flow_report(goodQty=1.5), "HTTP 400")
    s.post("양품 null", RESULTS, flow_report(goodQty=None), "HTTP 400")
    s.post("woId 문자열 (없는 작업지시)", RESULTS, flow_report(woId="99999999"), "E004")
    s.post("깨진 JSON", RESULTS, raw='{"woId": ', expect="HTTP 400")
    # C#은 형식을 통과시키고 작업지시 확인(E004)에서, Java는 형식 확인(E001)에서 걸린다. 어느 쪽도 반영하지 않는다
    s.post("보고 시각 2026/11/03 08:00 (없는 작업지시)", RESULTS,
           flow_report(woId=99999999, reportedAt="2026/11/03 08:00"), "E004", known=("reported-at-format", "E001"))

    s.section("실적 보고: 반영")
    # 오프셋이 없는 시각은 서버 시각으로 본다. 근무조 경계(06, 14, 22시)와 자정 넘김을 지나가게 골랐다
    s.post("08:00+09:00", RESULTS, flow_report(reportedAt=f"{DAY}T08:00:00+09:00"))
    s.post("13:59:59 (오프셋 없음)", RESULTS, flow_report(reportedAt=f"{DAY}T13:59:59"))
    s.post("14:00 (날짜와 시각 사이 공백)", RESULTS, flow_report(reportedAt=f"{DAY} 14:00:00"))
    s.post("21:59:59 불량만 → LOT 없음", RESULTS,
           flow_report(goodQty=0, defectQty=1, defectCd="D01", reportedAt=f"{DAY}T21:59:59"))
    s.post("22:00", RESULTS, flow_report(reportedAt=f"{DAY}T22:00:00"))
    s.post("다음 날 05:59:59 → 전날 근무일자", RESULTS, flow_report(reportedAt=f"{NEXT_DAY}T05:59:59"))
    s.post("다음 날 06:00 → LOT 순번 0001부터", RESULTS, flow_report(reportedAt=f"{NEXT_DAY}T06:00:00"))
    s.post("날짜만 → 그날 00:00", RESULTS, flow_report(reportedAt=NEXT_DAY))
    s.post("키 대소문자가 다름, 검사 공정 불량 2", RESULTS,
           {"WoId": Var("wo_flow"), "ProcCd": "INSP", "EqpCd": "EQ-INSP-01", "GoodQty": 0, "DefectQty": 2,
            "DefectCd": "D03", "ReportedAt": f"{DAY}T15:00:00"})
    s.post("양품 1 + 불량 1", RESULTS, flow_report(defectQty=1, defectCd="D02", reportedAt=f"{DAY}T16:00:00"))
    s.post("불량 0인데 불량 코드 있음", RESULTS, flow_report(defectCd="D01", reportedAt=f"{DAY}T16:30:00"))
    s.post("보고 시각 없음 → 서버 현재 시각", RESULTS, flow_report(goodQty=0, defectQty=1, defectCd="D01"))
    s.post("양품 3 → 누적 12, 계획 10을 넘겨 자동 완료", RESULTS, flow_report(goodQty=3, reportedAt=f"{DAY}T17:00:00"))
    s.post("자동 완료 뒤 보고", RESULTS, flow_report(reportedAt=f"{DAY}T17:10:00"), "E006")
    s.post("자동 완료 뒤 수동 완료", f"{flow}/complete", expect="E005")
    # 같은 공장·근무일자면 작업지시가 달라도 LOT 순번이 이어진다
    s.post("SUV 도장 양품 2", RESULTS,
           report(Var("wo_suv"), procCd="PAINT", eqpCd="EQ-PAINT-01", goodQty=2, reportedAt=f"{DAY}T10:00:00"))
    s.post("SUV 의장 양품 1", RESULTS,
           report(Var("wo_suv"), procCd="ASSY", eqpCd="EQ-ASSY-01", reportedAt=f"{DAY}T10:30:00"))
    # 앨라배마 현지 시각 + 오프셋. 서버 시간대로 바꿔서 근무조를 계산하는 레거시 버그를 Java도 그대로 갖고 있다 (5단계에서 고침)
    s.post("앨라배마 현지 08:00 (-06:00)", RESULTS, report(Var("wo_us"), reportedAt=f"{DAY}T08:00:00-06:00"))
    s.post("앨라배마 현지 18:00 (-06:00)", RESULTS, report(Var("wo_us"), reportedAt=f"{DAY}T18:00:00-06:00"))
    # 누적 수량이 int 범위를 넘어 DB 오류(E999)가 나는 보고. LOT·실적을 저장한 뒤에 실패하므로 모두 롤백되어야 한다
    s.post("계획 최댓값: 양품 1", RESULTS, report(Var("wo_big"), reportedAt=f"{DAY}T11:00:00"))
    s.post("계획 최댓값: 누적이 int 범위를 넘음", RESULTS,
           report(Var("wo_big"), goodQty=2147483647, reportedAt=f"{DAY}T11:30:00"), "E999",
           known=("E999-message", "E999"))

    s.section("반영 결과 조회")
    s.get("자동 완료된 작업지시", flow)
    s.get("LOT 목록", f"{flow}/lots")
    s.get("실적 이력", f"{flow}/results")
    s.get("SUV LOT 목록", "/api/work-orders/$wo_suv/lots")
    s.get("앨라배마 실적 이력", "/api/work-orders/$wo_us/results")
    s.get("계획 최댓값: 실패한 보고는 수량에 없음", "/api/work-orders/$wo_big")
    s.get("계획 최댓값: 실패한 보고는 LOT에 없음", "/api/work-orders/$wo_big/lots")
    s.get("목록: 울산 전체", "/api/work-orders?plantCd=KR01")
    s.get("목록: 울산 COMPLETED", "/api/work-orders?plantCd=KR01&status=COMPLETED")
    s.get("목록: 울산 IN_PROGRESS", "/api/work-orders?plantCd=KR01&status=IN_PROGRESS")

    s.section("주소·파라미터 표기")
    s.get("주소 끝에 슬래시", "/api/plants/")
    s.get("주소 대소문자", "/API/Plants")
    s.get("파라미터 이름 대소문자", "/api/items?plantcd=KR01")
    s.get("파라미터 이름 대문자", "/api/work-orders?PLANTCD=KR01&STATUS=COMPLETED")
    s.get("같은 파라미터 두 번", "/api/items?plantCd=KR01&plantCd=US01")
    s.post("POST 주소 끝에 슬래시", "/api/work-orders/$wo_suv/release/", expect="E005")

    s.section("HTTP 오류")
    s.get("없는 주소", "/api/nope", "HTTP 404")
    s.get("/api", "/api", "HTTP 404")
    s.call("HEAD", "HEAD 요청", "/api/plants", "HTTP 405", known=("head-options", "HTTP 200"))
    s.call("OPTIONS", "OPTIONS 요청", "/api/plants", "HTTP 405", known=("head-options", "HTTP 200"))
    s.call("DELETE", "작업지시 삭제 (없는 기능)", flow, "HTTP 405")
    s.call("PUT", "실적 PUT", RESULTS, "HTTP 405", body=flow_report())

    return s.steps
