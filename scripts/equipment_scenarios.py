"""설비 TCP 게이트웨이 장애 시나리오.

가짜 PLC(plc_simulator.py)로 설비 현장에서 생기는 일을 일부러 만들고, 실적이 빠지거나 두 번 들어가지 않는지
DB에서 확인한다. 시나리오마다 새 작업지시를 하나씩 쓴다.

  python scripts/equipment_scenarios.py              # 빌드 → 시나리오용 DB → 시나리오용 WAS → 시나리오
  python scripts/equipment_scenarios.py --no-build   # 이미 빌드한 jar로 실행

개발용 WAS(:8081, :9101)와 개발용 DB(mes)는 건드리지 않는다. 동등성 검증과 같은 방법으로 따로 띄운다.
  시나리오용 WAS   HTTP :8182, 게이트웨이 :9102. 끝나면 내린다. restart 시나리오에서는 도중에 강제 종료했다가 다시 띄운다
  시나리오용 DB    mes_equip_scenario. 실행할 때마다 새로 만들고, 끝난 뒤에도 남겨 둬서 직접 볼 수 있다
  WAS 로그         build/equipment-scenarios/was.log

하나라도 기대와 다르면 종료 코드 1, 시작하지 못하면 2.
"""
import argparse
import datetime
import json
import os
import socket
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from equivalence import ROOT, DB_PORT, JAVA_PROJECT, SetupError, check_database, psql, reset_database, run, tail, timed
from plc_simulator import Plc

RUN_DIR = ROOT / "build" / "equipment-scenarios"
DB = "mes_equip_scenario"
HTTP_PORT, GATEWAY_PORT = 8182, 9102
BASE = f"http://localhost:{HTTP_PORT}"
EQP = "EQ-BODY-01"
LOG = RUN_DIR / "was.log"


# ---------------------------------------------------------------- WAS

class Was:
    def __init__(self):
        self.process = None

    def start(self):
        jars = [p for p in (JAVA_PROJECT / "build" / "libs").glob("*.jar") if not p.name.endswith("-plain.jar")]
        if not jars:
            raise SetupError("mes-api/build/libs에 jar가 없습니다. --no-build 없이 실행해서 먼저 빌드하세요.")
        jar = max(jars, key=lambda p: p.stat().st_mtime)
        java = Path(os.environ["JAVA_HOME"]) / "bin" / "java" if os.environ.get("JAVA_HOME") else "java"
        with open(LOG, "ab") as log:                   # 재시작해도 로그를 이어 쓴다
            self.process = subprocess.Popen(
                [str(java), "-jar", str(jar), f"--server.port={HTTP_PORT}", f"--mes.equipment.port={GATEWAY_PORT}",
                 f"--spring.datasource.url=jdbc:postgresql://localhost:{DB_PORT}/{DB}"],
                cwd=JAVA_PROJECT, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        started = time.perf_counter()
        while time.perf_counter() - started < 90:
            if self.process.poll() is not None:
                raise SetupError(f"WAS가 시작하지 못했습니다. 로그: {LOG}\n{tail(LOG)}")
            try:
                with urllib.request.urlopen(BASE + "/api/plants", timeout=2):
                    return time.perf_counter() - started
            except OSError:
                time.sleep(0.2)
        raise SetupError(f"WAS가 90초 안에 뜨지 않았습니다. 로그: {LOG}\n{tail(LOG)}")

    def kill(self):
        """정상 종료 절차 없이 프로세스를 끝낸다 (Windows에서는 terminate도 TerminateProcess라 같음)"""
        if self.process and self.process.poll() is None:
            self.process.kill()
            self.process.wait(timeout=10)


def check_ports():
    for port in (HTTP_PORT, GATEWAY_PORT):
        with socket.socket() as sock:
            sock.settimeout(0.5)
            if sock.connect_ex(("127.0.0.1", port)) == 0:
                raise SetupError(f"포트 {port}을 이미 다른 프로세스가 쓰고 있습니다. "
                                 f"이전 실행에서 남은 WAS라면 종료하고 다시 실행하세요.")


# ---------------------------------------------------------------- 준비와 확인

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as res:
        result = json.loads(res.read())
    if result["resultCode"] != "0000":
        raise SetupError(f"{method} {path} 실패: {result}")
    return result["data"]


def started_work_order(plan_qty=1000):
    wo = call("POST", "/api/work-orders", {"plantCd": "KR01", "itemCd": "SEDAN-A", "planQty": plan_qty,
                                           "planDate": datetime.date.today().isoformat()})
    call("POST", f"/api/work-orders/{wo['wo_id']}/release")
    call("POST", f"/api/work-orders/{wo['wo_id']}/start")
    return wo["wo_id"]


@dataclass
class Counts:
    results: int        # production_result 행
    lots: int           # 서로 다른 LOT 번호
    messages: int       # equipment_message 행 (처리한 메시지 ID)
    good_qty: int       # work_order.good_qty

    def __str__(self):
        return f"실적 {self.results}, LOT {self.lots}, 메시지 {self.messages}, 양품 {self.good_qty}"


def counts(wo_id):
    row = psql(DB, "-F", ",", "-c", f"""
        SELECT (SELECT count(*) FROM production_result WHERE wo_id = {wo_id}),
               (SELECT count(DISTINCT lot_no) FROM production_result WHERE wo_id = {wo_id}),
               (SELECT count(*) FROM equipment_message WHERE wo_id = {wo_id}),
               (SELECT good_qty FROM work_order WHERE wo_id = {wo_id})""").strip()
    return Counts(*map(int, row.split(",")))


@dataclass
class Outcome:
    name: str
    passed: bool
    detail: str


def quiet(_message):
    pass


# ---------------------------------------------------------------- 시나리오

def fault_scenario(name, fault, count):
    """같은 장애를 count번 일으키며 실적을 보낸다. 실적은 count건, 메시지도 count건이어야 한다"""
    wo_id = started_work_order()
    with Plc(EQP, port=GATEWAY_PORT, log=quiet) as plc:
        for _ in range(count):
            _, reply = plc.report(wo_id, 1, fault=fault)
            if reply.kind != "ACK":
                return Outcome(name, False, f"NAK {reply.fields}")
        stats = plc.stats
    expected = Counts(count, count, count, count)
    actual = counts(wo_id)
    return Outcome(name, actual == expected,
                   f"보냄 {stats.sent}, 재전송 {stats.resends}, 재접속 {stats.reconnects} → {actual}")


def restart_scenario(was, count=30, kill_after=10):
    """실적을 보내는 도중 WAS를 강제 종료하고 다시 띄운다.
    처리 도중 죽으면 트랜잭션이 롤백되거나(재전송이 새로 반영됨), 커밋 뒤 ACK를 보내기 전에 죽는다(재전송이 처음 ACK를 받음).
    어느 쪽이든 실적은 정확히 count건이어야 한다"""
    wo_id = started_work_order()
    plc = Plc(EQP, port=GATEWAY_PORT, ack_timeout=3.0, log=quiet)
    acked_at = []
    error = []

    def device():
        try:
            with plc:
                for _ in range(count):
                    _, reply = plc.report(wo_id, 1)
                    if reply.kind != "ACK":
                        raise AssertionError(f"NAK {reply.fields}")
                    acked_at.append(time.perf_counter())
                    time.sleep(0.05)
        except Exception as e:                         # noqa: BLE001 - 메인 스레드에서 보고한다
            error.append(e)

    thread = threading.Thread(target=device)
    thread.start()
    while len(acked_at) < kill_after and thread.is_alive():
        time.sleep(0.01)
    was.kill()
    killed_at = time.perf_counter()
    startup = was.start()
    thread.join(timeout=120)
    if error or thread.is_alive():
        return Outcome("WAS 강제 종료 후 재시작", False, f"설비 쪽 오류: {error or '끝나지 않음'}")

    # 끊긴 뒤 처음 ACK를 받기까지 걸린 시간 = 설비 입장에서 실적을 못 보낸 시간
    resumed = min(t for t in acked_at if t > killed_at)
    stats = plc.stats
    expected = Counts(count, count, count, count)
    actual = counts(wo_id)
    return Outcome("WAS 강제 종료 후 재시작", actual == expected,
                   f"보냄 {stats.sent}, 재전송 {stats.resends}, 재접속 {stats.reconnects}, "
                   f"WAS 기동 {startup:.1f}초, 중단 {resumed - killed_at:.1f}초 → {actual}")


def connect_times(host, times=5):
    """접속에 걸리는 시간(ms). 접속만 하고 바로 닫는다.
    timeout을 주면 Python이 접속 완료를 select로 기다리는데, Windows에서는 이 대기가 타이머 단위(약 15ms)로 늘어나서
    접속 자체의 시간이 가려진다. 그래서 timeout 없이(블로킹) 잰다. Windows는 연결 거부를 약 2초 안에 포기하므로 멈추지 않는다"""
    measured = []
    for _ in range(times):
        started = time.perf_counter()
        with socket.create_connection((host, GATEWAY_PORT), timeout=None):
            measured.append((time.perf_counter() - started) * 1000)
    return measured


def localhost_measurement():
    addresses = [info[4][0] for info in socket.getaddrinfo("localhost", GATEWAY_PORT, type=socket.SOCK_STREAM)]
    lines = [f"localhost → {', '.join(addresses)} 순서로 시도"]
    for host in ("127.0.0.1", "localhost"):
        ms = connect_times(host)
        lines.append(f"{host:>9}: 중앙값 {statistics.median(ms):7.1f}ms  (최소 {min(ms):.1f}, 최대 {max(ms):.1f})")
    return lines


# ---------------------------------------------------------------- 실행

def main():
    parser = argparse.ArgumentParser(description="설비 TCP 게이트웨이 장애 시나리오")
    parser.add_argument("--no-build", action="store_true", help="빌드를 건너뛰고 이미 만든 jar로 실행")
    args = parser.parse_args()

    RUN_DIR.mkdir(parents=True, exist_ok=True)
    LOG.unlink(missing_ok=True)
    was = Was()
    print("준비")
    try:
        timed("DB 연결 확인", check_database)
        check_ports()
        if not args.no_build:
            gradlew = JAVA_PROJECT / ("gradlew.bat" if os.name == "nt" else "gradlew")
            timed("Java 빌드 (bootJar)", lambda: run([gradlew, "bootJar", "-q"], cwd=JAVA_PROJECT))
        timed(f"시나리오용 DB ({DB})", lambda: reset_database(DB))
        timed(f"WAS 기동 (:{HTTP_PORT}, 게이트웨이 :{GATEWAY_PORT})", was.start)

        print("\n시나리오")
        outcomes = []
        for run_scenario in (lambda: fault_scenario("정상 전송", None, 20),
                             lambda: fault_scenario("전문 쪼개 보내기", "split", 5),
                             lambda: fault_scenario("ACK 유실 뒤 재전송", "lose-ack", 5),
                             lambda: fault_scenario("절반 보내고 끊김", "cut", 5),
                             lambda: restart_scenario(was)):
            outcome = run_scenario()
            outcomes.append(outcome)
            print(f"  [{'통과' if outcome.passed else '실패'}] {outcome.name}: {outcome.detail}")

        print("\nlocalhost 접속 시간 (측정만, 판정 없음)")
        for line in localhost_measurement():
            print(f"  {line}")
    except SetupError as e:
        print(f"\n시작하지 못했습니다: {e}")
        return 2
    finally:
        was.kill()

    failed = [o for o in outcomes if not o.passed]
    print(f"\n결과: {len(outcomes) - len(failed)}/{len(outcomes)} 통과. WAS 로그: {LOG.relative_to(ROOT)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
