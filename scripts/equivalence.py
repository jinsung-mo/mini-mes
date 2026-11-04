"""C# 레거시와 Java WAS의 동등성 검증.

같은 요청을 같은 순서로 두 WAS에 보내서, 응답과 DB에 남은 결과가 같은지 비교한다.
두 WAS는 같은 초기 스크립트(db/init)로 새로 만든 DB를 하나씩 따로 쓴다. 같은 상태에서 출발하므로 등록·변경 요청까지
보낼 수 있고, wo_id·작업지시 번호·LOT 번호 같은 채번 결과도 가리지 않고 그대로 비교한다.

  python scripts/equivalence.py              # 빌드 → 검증용 DB → 검증용 WAS → 시나리오 → 비교
  python scripts/equivalence.py --no-build   # 이미 빌드한 결과로 실행
  python scripts/equivalence.py -v           # 같은 요청까지 모두 출력

개발용 WAS(:5080, :8081)와 개발용 DB(mes)는 건드리지 않는다.
  검증용 WAS   C# :5180, Java :8181. 끝나면 내린다
  검증용 DB    mes_equiv_legacy, mes_equiv_java. 실행할 때마다 새로 만들고, 끝난 뒤에도 남겨 둬서 직접 볼 수 있다
  결과 파일    build/equivalence/ (report.json, WAS 로그)

판정
  같음         응답과 DB가 같다
  의도한 차이   레거시와 일부러 다르게 만든 동작. equivalence_scenario.py에 이유와 함께 등록한 것만 해당
  다름         그 밖의 모든 차이
  기대와 다름   C# 응답이 시나리오에 적은 기대 결과와 다르다. 시나리오가 레거시를 잘못 적었거나 레거시가 바뀌었다
'다름'이나 '기대와 다름'이 하나라도 있으면 종료 코드 1, 검증을 시작하지 못하면 2.
"""
import argparse
import collections
import datetime
import itertools
import json
import os
import socket
import statistics
import string
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

import equivalence_scenario as scenario

ROOT = Path(__file__).resolve().parent.parent
RUN_DIR = ROOT / "build" / "equivalence"
LEGACY_PROJECT = ROOT / "legacy-csharp" / "LegacyMes"
JAVA_PROJECT = ROOT / "mes-api"

DB_CONTAINER = "mini-mes-postgres"
DB_PORT = 55432

# 서버 시계로 채우는 값. 두 WAS가 몇 ms 차이로 처리하고 응답은 초 단위라 똑같을 수 없으므로, 2초 안이면 같다고 본다.
# 시간대를 잘못 다루면 몇 시간씩 어긋나고, 다른 상태 전이에서 채우면 한쪽만 값이 있어서 이 허용 범위에 가려지지 않는다.
# 시각을 안 바꾸는 실수(updated_at 누락)는 단계 사이가 2초보다 짧으면 가려지므로, 시나리오가 그런 단계 앞에서 3초 쉰다.
TIME_FIELDS = {"started_at", "completed_at", "created_at", "updated_at", "reported_at"}
TIME_TOLERANCE = datetime.timedelta(seconds=2)

SAME, KNOWN, DIFFERENT, UNEXPECTED = "같음", "의도한 차이", "다름", "기대와 다름"
KINDS = (SAME, KNOWN, DIFFERENT, UNEXPECTED)
FAILURES = (DIFFERENT, UNEXPECTED)


@dataclass
class Was:
    key: str
    label: str
    port: int
    db: str
    process: subprocess.Popen | None = None

    @property
    def base(self):
        return f"http://localhost:{self.port}"

    @property
    def log_path(self):
        return RUN_DIR / f"{self.key}.log"


LEGACY = Was("legacy", "C#", 5180, "mes_equiv_legacy")
JAVA = Was("java", "Java", 8181, "mes_equiv_java")


class SetupError(Exception):
    """검증을 시작하지 못함 (빌드 실패, DB 없음, WAS가 뜨지 않음 등)"""


class _Absent:
    def __repr__(self):
        return "(키 없음)"


ABSENT = _Absent()


# ---------------------------------------------------------------- 준비: 빌드, DB, WAS

def run(cmd, cwd=None, input=b"", env=None):
    try:
        res = subprocess.run([str(c) for c in cmd], cwd=cwd, input=input, capture_output=True, env=env)
    except FileNotFoundError:
        raise SetupError(f"명령을 찾을 수 없습니다: {cmd[0]}") from None
    if res.returncode != 0:
        output = (res.stdout + res.stderr).decode("utf-8", "replace").strip()
        raise SetupError(f"실패: {' '.join(str(c) for c in cmd[:3])} ...\n{output[-3000:]}")
    return res.stdout.decode("utf-8", "replace")


def timed(label, action):
    print(f"  {label} ...", end=" ", flush=True)
    started = time.perf_counter()
    result = action()
    print(f"{time.perf_counter() - started:.1f}초")
    return result


def build():
    env = {**os.environ, "DOTNET_CLI_UI_LANGUAGE": "en"}
    timed("C# 빌드 (Release)",
          lambda: run(["dotnet", "build", LEGACY_PROJECT, "-c", "Release", "--nologo", "-v", "q"], env=env))
    gradlew = JAVA_PROJECT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    timed("Java 빌드 (bootJar)", lambda: run([gradlew, "bootJar", "-q"], cwd=JAVA_PROJECT))


def psql(db, *args, input=b""):
    return run(["docker", "exec", "-i", "-e", "PGCLIENTENCODING=UTF8", DB_CONTAINER,
                "psql", "-U", "mes", "-d", db, "-v", "ON_ERROR_STOP=1", "-q", "-At", *args], input=input)


def check_database():
    try:
        psql("mes", "-c", "SELECT 1")
    except SetupError as e:
        raise SetupError(f"DB 컨테이너({DB_CONTAINER})에 연결할 수 없습니다. "
                         f"레포 루트에서 docker compose up -d 를 먼저 실행하세요.\n{e}") from None


def reset_database(db):
    """DB를 지우고 새로 만든 뒤, docker-entrypoint-initdb.d와 같은 순서(파일 이름순)로 db/init 스크립트를 실행한다"""
    psql("mes", "-c", f"DROP DATABASE IF EXISTS {db} WITH (FORCE)", "-c", f"CREATE DATABASE {db}")
    scripts = sorted((ROOT / "db" / "init").glob("*.sql"))
    psql(db, input="\n".join(p.read_text(encoding="utf-8") for p in scripts).encode("utf-8"))


def check_ports():
    for was in (LEGACY, JAVA):
        with socket.socket() as sock:
            sock.settimeout(0.5)
            if sock.connect_ex(("127.0.0.1", was.port)) == 0:
                raise SetupError(f"포트 {was.port}을 이미 다른 프로세스가 쓰고 있습니다. "
                                 f"이전 검증에서 남은 WAS라면 종료하고 다시 실행하세요.")


def start(was, cmd, cwd):
    with open(was.log_path, "wb") as log:
        was.process = subprocess.Popen([str(c) for c in cmd], cwd=cwd, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT)


def start_legacy():
    dll = LEGACY_PROJECT / "bin" / "Release" / "net8.0" / "LegacyMes.dll"
    if not dll.exists():
        raise SetupError(f"{dll} 이 없습니다. --no-build 없이 실행해서 먼저 빌드하세요.")
    conn = f"Host=localhost;Port={DB_PORT};Database={LEGACY.db};Username=mes;Password=mes"
    # appsettings.json을 그대로 읽도록 빌드 폴더에서 실행하고, 주소와 DB만 명령줄 설정으로 덮어쓴다
    start(LEGACY, ["dotnet", dll, f"--urls={LEGACY.base}", f"--ConnectionStrings:Mes={conn}"], dll.parent)


def start_java():
    jars = [p for p in (JAVA_PROJECT / "build" / "libs").glob("*.jar") if not p.name.endswith("-plain.jar")]
    if not jars:
        raise SetupError("mes-api/build/libs에 jar가 없습니다. --no-build 없이 실행해서 먼저 빌드하세요.")
    jar = max(jars, key=lambda p: p.stat().st_mtime)
    java = Path(os.environ["JAVA_HOME"]) / "bin" / "java" if os.environ.get("JAVA_HOME") else "java"
    # application.yml을 그대로 쓰고, 포트와 DB만 명령줄 설정으로 덮어쓴다
    start(JAVA, [java, "-jar", jar, f"--server.port={JAVA.port}",
                 f"--spring.datasource.url=jdbc:postgresql://localhost:{DB_PORT}/{JAVA.db}"], JAVA_PROJECT)


def wait_ready(was, timeout=90):
    started = time.perf_counter()
    while time.perf_counter() - started < timeout:
        if was.process.poll() is not None:
            raise SetupError(f"{was.label} WAS가 시작하지 못했습니다. 로그: {was.log_path}\n{tail(was.log_path)}")
        try:
            with urllib.request.urlopen(was.base + "/api/plants", timeout=2):
                return
        except OSError:
            time.sleep(0.3)
    raise SetupError(f"{was.label} WAS가 {timeout}초 안에 뜨지 않았습니다. 로그: {was.log_path}\n{tail(was.log_path)}")


def tail(path, lines=30):
    try:
        return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])
    except OSError:
        return ""


def stop(was):
    if was.process and was.process.poll() is None:
        was.process.terminate()
        try:
            was.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            was.process.kill()


# ---------------------------------------------------------------- 요청 보내기

@dataclass
class Response:
    status: int | None   # None이면 연결 실패
    body: object         # JSON으로 읽은 본문. JSON이 아니면 None
    ms: float

    @property
    def contract(self):
        """응답 계약(HTTP 200 + resultCode)을 따르는 본문인가"""
        return isinstance(self.body, dict) and "resultCode" in self.body

    @property
    def code(self):
        if self.status is None:
            return "연결 실패"
        return self.body["resultCode"] if self.contract else f"HTTP {self.status}"


def send(was, step, variables):
    path = string.Template(step.path).safe_substitute(variables)
    data = None
    if step.raw is not None:
        data = string.Template(step.raw).safe_substitute(variables).encode("utf-8")
    elif step.body is not None:
        data = json.dumps(fill(step.body, variables), ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": step.content_type} if data is not None and step.content_type else {}
    request = urllib.request.Request(was.base + path, data=data, method=step.method, headers=headers)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=30) as res:
            status, raw = res.status, res.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    except OSError:
        return Response(None, None, (time.perf_counter() - started) * 1000)
    ms = (time.perf_counter() - started) * 1000
    try:
        body = json.loads(raw) if raw else None
    except ValueError:
        body = None
    return Response(status, body, ms)


def fill(value, variables):
    """본문 안의 Var를 이 WAS에서 저장해 둔 값으로 바꾼다"""
    if isinstance(value, scenario.Var):
        return variables.get(value.name, f"${value.name}")
    if isinstance(value, dict):
        return {k: fill(v, variables) for k, v in value.items()}
    if isinstance(value, list):
        return [fill(v, variables) for v in value]
    return value


def save(step, response, variables):
    for name, path in (step.save or {}).items():
        value = response.body
        for key in path.split("."):
            value = value.get(key) if isinstance(value, dict) else None
        if value is not None:
            variables[name] = value


# ---------------------------------------------------------------- 비교

def diff(a, b, path=""):
    """두 값의 다른 곳을 (경로, C# 값, Java 값) 목록으로 돌려준다. 키 순서는 보지 않고, 숫자와 문자열은 구분한다"""
    if isinstance(a, dict) and isinstance(b, dict):
        out = []
        for key in list(a) + [k for k in b if k not in a]:
            sub = f"{path}.{key}" if path else key
            if key not in a or key not in b:
                out.append((sub, a.get(key, ABSENT), b.get(key, ABSENT)))
            elif key in TIME_FIELDS and same_moment(a[key], b[key]):
                continue
            else:
                out.extend(diff(a[key], b[key], sub))
        return out
    if isinstance(a, list) and isinstance(b, list):
        out = []
        for i, (x, y) in enumerate(zip(a, b)):
            out.extend(diff(x, y, f"{path}[{i}]"))
        if len(a) != len(b):
            out.append((f"{path} 개수" if path else "개수", len(a), len(b)))
        return out
    return [] if type(a) is type(b) and a == b else [(path, a, b)]


def same_moment(a, b):
    if a == b:
        return True
    if not (isinstance(a, str) and isinstance(b, str)):
        return False
    try:
        return abs(datetime.datetime.fromisoformat(a) - datetime.datetime.fromisoformat(b)) <= TIME_TOLERANCE
    except (ValueError, TypeError):
        return False


def compare(legacy, java):
    if legacy.status != java.status:
        return [("HTTP 상태", legacy.status, java.status)]
    if legacy.contract and java.contract:
        return diff(legacy.body, java.body)
    if legacy.contract != java.contract:
        return [("응답 형식", legacy.body, java.body)]
    # 둘 다 resultCode가 없는 응답(400, 404 등)이면 상태 코드만 비교한다.
    # 본문은 ASP.NET과 Spring이 각자 만드는 오류 문서이고, 단말 화면도 이때는 HTTP 상태만 보여 준다(web/html/app.js)
    return []


@dataclass
class StepResult:
    step: scenario.Step
    legacy: Response
    java: Response
    kind: str
    diffs: list
    known: str | None = None
    note: str = ""


def judge(step, legacy, java):
    diffs = compare(legacy, java)
    if legacy.code != step.expect:
        return StepResult(step, legacy, java, UNEXPECTED, diffs,
                          note=f"C# {legacy.code}, 시나리오에 적은 기대 결과는 {step.expect}")
    if step.known:
        known, java_code = step.known
        if java.code == java_code and diffs:
            return StepResult(step, legacy, java, KNOWN, diffs, known=known)
        return StepResult(step, legacy, java, DIFFERENT, diffs,
                          note=f"의도한 차이({known})로 Java {java_code}를 기대했는데 Java {java.code}")
    return StepResult(step, legacy, java, DIFFERENT if diffs else SAME, diffs)


def run_steps(steps):
    variables = {LEGACY.key: {}, JAVA.key: {}}
    results = []
    for step in steps:
        if isinstance(step, scenario.Pause):
            time.sleep(step.seconds)
            continue
        # 같은 요청을 C# → Java 순서로 바로 이어서 보낸다. 서버 시각이 들어가는 값도 몇 ms 차이로 맞춰진다
        legacy = send(LEGACY, step, variables[LEGACY.key])
        java = send(JAVA, step, variables[JAVA.key])
        save(step, legacy, variables[LEGACY.key])
        save(step, java, variables[JAVA.key])
        results.append(judge(step, legacy, java))
    return results


@dataclass
class TableResult:
    name: str
    kind: str
    legacy: object
    java: object
    diffs: list = field(default_factory=list)
    known: str | None = None
    note: str = ""


TABLES_SQL = """
SELECT coalesce(jsonb_object_agg(t.table_name, pk.cols), '{}')
  FROM information_schema.tables t
  LEFT JOIN (SELECT c.relname AS table_name,
                    string_agg(quote_ident(a.attname), ', ' ORDER BY array_position(i.indkey::int2[], a.attnum)) AS cols
               FROM pg_index i
               JOIN pg_class c ON c.oid = i.indrelid
               JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
               JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY (i.indkey::int2[])
              WHERE i.indisprimary
              GROUP BY c.relname) pk ON pk.table_name = t.table_name
 WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
"""
SEQUENCES_SQL = """
SELECT coalesce(jsonb_agg(sequence_name ORDER BY sequence_name), '[]')
  FROM information_schema.sequences WHERE sequence_schema = 'public'
"""


def schema(db):
    """테이블(→ 기본키 컬럼)과 시퀀스 목록. 테이블이 새로 생겨도 비교에서 빠지지 않게 DB에서 읽는다"""
    tables, sequences = psql(db, "-c", TABLES_SQL, "-c", SEQUENCES_SQL).splitlines()
    return json.loads(tables), json.loads(sequences)


def snapshot(db, tables, sequences):
    """테이블은 기본키 순서로 모든 행을, 시퀀스는 마지막 값을 읽는다"""
    commands = []
    for name, keys in tables.items():
        order = ", ".join(f"x.{k}" for k in keys.split(", ")) if keys else "x"
        commands += ["-c", f"SELECT coalesce(jsonb_agg(x ORDER BY {order}), '[]') FROM \"{name}\" x"]
    for name in sequences:
        commands += ["-c", f"SELECT jsonb_build_object('last_value', last_value, 'is_called', is_called) FROM \"{name}\""]
    lines = psql(db, *commands).splitlines()
    return dict(zip([*tables, *sequences], map(json.loads, lines)))


def compare_databases():
    (legacy_tables, legacy_seqs), (java_tables, java_seqs) = schema(LEGACY.db), schema(JAVA.db)
    results = []
    legacy_names, java_names = {*legacy_tables, *legacy_seqs}, {*java_tables, *java_seqs}
    if legacy_names != java_names:
        # 한쪽 WAS만 테이블을 만들었다 (예: 시작할 때 마이그레이션을 돌리는 WAS). 양쪽에 다 있는 것만 아래에서 비교한다
        results.append(TableResult("테이블·시퀀스 구성", DIFFERENT, len(legacy_names), len(java_names),
                                   note=f"C#에만 {sorted(legacy_names - java_names)}, "
                                        f"Java에만 {sorted(java_names - legacy_names)}"))
    tables = {name: keys for name, keys in sorted(legacy_tables.items()) if name in java_tables}
    sequences = [name for name in legacy_seqs if name in java_seqs]
    legacy_db, java_db = snapshot(LEGACY.db, tables, sequences), snapshot(JAVA.db, tables, sequences)
    for name in [*tables, *sequences]:
        a, b = legacy_db[name], java_db[name]
        diffs = diff(a, b)
        if not diffs:
            results.append(TableResult(name, SAME, a, b))
        elif name in scenario.KNOWN_TABLES:
            known, check = scenario.KNOWN_TABLES[name]
            problem = check(a, b, java_db)
            results.append(TableResult(name, DIFFERENT if problem else KNOWN, a, b, diffs, known, problem or ""))
        else:
            results.append(TableResult(name, DIFFERENT, a, b, diffs))
    return results


# ---------------------------------------------------------------- 결과 출력

def short(value, limit=90):
    text = repr(value) if value is ABSENT else json.dumps(value, ensure_ascii=False)
    return text if len(text) <= limit else text[:limit - 3] + "..."


def size(value):
    return f"{len(value)}행" if isinstance(value, list) else short(value, 40)


def print_diffs(diffs, indent, limit=6):
    for path, a, b in diffs[:limit]:
        print(f"{indent}{path or '(값)'}: C# {short(a)} / Java {short(b)}")
    if len(diffs) > limit:
        print(f"{indent}... 외 {len(diffs) - limit}곳")


def print_report(step_results, table_results, verbose):
    print(f"\n요청 비교: 같은 요청 {len(step_results)}건을 C# → Java 순서로 보냄")
    for section, group in itertools.groupby(step_results, key=lambda r: r.step.section):
        group = list(group)
        counts = collections.Counter(r.kind for r in group)
        print(f"  {section:<16} {len(group):>3}건  " + " · ".join(f"{k} {counts[k]}" for k in KINDS if counts[k]))
        for r in group:
            if r.kind == SAME and not verbose:
                continue
            codes = r.legacy.code if r.legacy.code == r.java.code else f"C# {r.legacy.code} / Java {r.java.code}"
            known = f"  → {scenario.KNOWN_DIFFERENCES[r.known].title}" if r.known else ""
            print(f"      [{r.kind}] {r.step.name}  ({codes}){known}")
            if r.note:
                print(f"          {r.note}")
            if r.kind in FAILURES:
                print_diffs(r.diffs, "          ")

    print(f"\nDB 비교: public 스키마의 테이블 {sum(isinstance(t.legacy, list) for t in table_results)}개와 "
          f"시퀀스 {sum(isinstance(t.legacy, dict) for t in table_results)}개")
    for t in table_results:
        counts = size(t.legacy) if t.kind == SAME else f"C# {size(t.legacy)} / Java {size(t.java)}"
        known = f"  → {scenario.KNOWN_DIFFERENCES[t.known].title}" if t.known else ""
        print(f"  {t.kind:<6} {t.name:<34} {counts}{known}")
        if t.note:
            print(f"         {t.note}")
        if t.kind in FAILURES:
            print_diffs(t.diffs, "         ")

    seen = collections.Counter(r.known for r in [*step_results, *table_results] if r.known and r.kind == KNOWN)
    print("\n의도한 차이: 레거시와 일부러 다르게 만든 동작 (equivalence_scenario.KNOWN_DIFFERENCES)")
    for key, kd in scenario.KNOWN_DIFFERENCES.items():
        print(f"  {kd.title}  {seen[key]}건")
        print(f"      C#   {kd.legacy}")
        print(f"      Java {kd.java}")
        print(f"      이유 {kd.reason} ({kd.doc})")

    step_counts = collections.Counter(r.kind for r in step_results)
    table_counts = collections.Counter(t.kind for t in table_results)
    failures = [r for r in step_results if r.kind in FAILURES]
    passed = not failures and not any(t.kind in FAILURES for t in table_results)

    def summary(counts):
        return ", ".join(f"{k} {counts[k]}" for k in KINDS if counts[k])

    print(f"\n결과: {'통과' if passed else '실패'}")
    print(f"  요청 {len(step_results)}건: {summary(step_counts)}")
    print(f"  DB {len(table_results)}개: {summary(table_counts)}")
    if failures:
        first = failures[0]
        print(f"  첫 차이: [{first.step.section}] {first.step.name} (뒤의 차이는 이 차이 때문에 생겼을 수 있음)")
    medians = [f"{was.label} {statistics.median(getattr(r, was.key).ms for r in step_results):.1f}ms"
               for was in (LEGACY, JAVA)]
    print(f"  응답 시간 중앙값: {', '.join(medians)}")
    print(f"  결과 파일: {RUN_DIR.relative_to(ROOT).as_posix()}/ (report.json, legacy.log, java.log)")
    print(f"  검증용 DB: {LEGACY.db}, {JAVA.db} (다음 실행 때 새로 만듦)")
    return passed


def write_report(step_results, table_results, passed):
    def response(r):
        return {"status": r.status, "body": r.body, "ms": round(r.ms, 1)}

    def diffs(items):
        return [[path, a, b] for path, a, b in items]

    report = {
        "passed": passed,
        "was": {was.key: {"url": was.base, "db": was.db} for was in (LEGACY, JAVA)},
        "steps": [{
            "section": r.step.section, "name": r.step.name, "method": r.step.method, "path": r.step.path,
            "body": r.step.body if r.step.raw is None else r.step.raw, "expect": r.step.expect,
            "kind": r.kind, "known": r.known, "note": r.note, "diffs": diffs(r.diffs),
            "legacy": response(r.legacy), "java": response(r.java),
        } for r in step_results],
        "db": [{"name": t.name, "kind": t.kind, "known": t.known, "note": t.note, "diffs": diffs(t.diffs)}
               for t in table_results],
    }
    with open(RUN_DIR / "report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2, default=repr)


def main():
    sys.stdout.reconfigure(encoding="utf-8")  # 파이프로 받을 때도 한글이 깨지지 않게 (Windows 기본은 cp949)
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--no-build", action="store_true", help="빌드하지 않고 이미 만든 결과로 실행")
    parser.add_argument("-v", "--verbose", action="store_true", help="같은 요청까지 모두 출력")
    args = parser.parse_args()
    RUN_DIR.mkdir(parents=True, exist_ok=True)

    print(f"동등성 검증: C# {LEGACY.base} (DB {LEGACY.db})  ↔  Java {JAVA.base} (DB {JAVA.db})")
    try:
        check_ports()
        check_database()
        if not args.no_build:
            build()
        timed("검증용 DB 새로 만들기 (db/init)", lambda: [reset_database(was.db) for was in (LEGACY, JAVA)])
        try:
            start_legacy()
            start_java()
            timed("검증용 WAS 실행", lambda: [wait_ready(was) for was in (LEGACY, JAVA)])
            step_results = run_steps(scenario.steps())
            table_results = compare_databases()
        finally:
            for was in (LEGACY, JAVA):
                stop(was)
    except SetupError as e:
        print(f"\n검증을 시작하지 못했습니다.\n{e}", file=sys.stderr)
        return 2

    passed = print_report(step_results, table_results, args.verbose)
    write_report(step_results, table_results, passed)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
