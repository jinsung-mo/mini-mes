# 트러블 슈팅

1~4번은 실적 보고 API를 Java로 옮긴 뒤, 여러 단말이 같은 순간에 실적을 보내는 상황을 일부러 만들어 찾고 고친 기록입니다.
5·6번은 3단계에서 C#과 Java의 [동등성 검증](03-equivalence.md)을 자동화하면서 찾았습니다.
뒤쪽의 [개발 중 만난 문제](#개발-중-만난-문제)는 전환 작업을 하면서 부딪힌 것들입니다.

수치는 모두 이 저장소의 [재현 스크립트](../scripts/report_race.py)와 [동등성 검증 스크립트](../scripts/equivalence.py)로 직접 측정한 값입니다. (Windows 11, Docker Desktop, PostgreSQL 16)

## 요약

| # | 문제 | 원인 | 해결 | 결과 |
|---|---|---|---|---|
| 1 | 동시 보고 시 LOT 번호가 겹쳐 실적이 사라짐 | `MAX + 1` 채번 | 채번 테이블 + 행 잠금 | 40건 중 성공 2~14건 → 40건 |
| 2 | 완료된 작업지시에 실적이 계속 반영됨 | 잠그지 않고 상태 확인 | 작업지시 행 잠금 (`FOR UPDATE`) | 계획 10에 양품 18~30 → 10 |
| 3 | 오류 응답에 서버 파일 경로와 SQL이 보임 | 예외 메시지를 그대로 응답 | 참조 번호만 응답, 상세는 서버 로그 | 노출 없음, 로그로 추적 |
| 4 | `localhost`로 부르면 Java만 요청마다 2초 | Java만 IPv4 루프백에서 받음 | `::1` 커넥터 추가 | 2,030ms → 4ms |
| 5 | 주소·파라미터 표기가 조금 다르면 Java만 거절 | ASP.NET과 Spring의 요청 해석 기본값 차이 | 요청 해석 규칙을 레거시에 맞춤 | 차이 12건 → 의도한 차이 2건 |
| 6 | 동등성 검증이 `updated_at` 누락을 놓침 | 시각 허용 범위(5초)가 단계 사이 간격보다 김 | 허용 범위 2초 + 단계 사이 3초 대기 | 넣은 실수 5가지 중 4가지 잡음 |

## 왜 동시 실적 보고인가

- **실적 보고는 MES에서 가장 자주 일어나는 쓰기입니다.** 공정마다 단말과 설비가 있어서 보고가 같은 순간에 겹칩니다.
- **LOT 번호 같은 업무 번호는 직접 채번합니다.** `L-KR01-261103-0001`처럼 공장·날짜·순번으로 된 번호는 DB 시퀀스로 바로 만들 수 없어서 코드로 다음 번호를 계산하게 되고, 동시성 문제가 여기서 잘 생깁니다.
- **요청을 하나씩 보내는 테스트로는 드러나지 않습니다.** 실적 보고 API를 옮긴 뒤 C#과 Java에 같은 요청 33건을 보내 응답이 모두 같다는 것을 확인했지만, 둘 다 아래 1·2번 문제를 똑같이 갖고 있었습니다.

## 재현 방법

```bash
docker compose up -d
cd legacy-csharp/LegacyMes && dotnet run      # C# 레거시 :5080
cd mes-api && ./gradlew bootRun               # Java :8081
python scripts/report_race.py                 # 두 WAS × 두 시나리오
python scripts/report_race.py --was java --repeat 3
```

| 시나리오 | 보내는 요청 | 맞게 동작하면 |
|---|---|---|
| `lot` | 작업지시 4개에 10건씩, 40건을 동시에. 모두 같은 공장·같은 근무일자 | 40건 모두 성공 |
| `complete` | 계획 수량 10인 작업지시 하나에 양품 1개씩 30건을 동시에 | 10건 성공(10번째에 자동 완료), 20건 E006, 최종 양품 10 |

두 시나리오 모두 끝나면 작업지시 누적 수량, LOT 수량 합계, 실적 수량 합계가 서로 맞는지도 확인합니다.

## 1. 동시 보고 시 LOT 번호 중복

### 증상

`lot` 시나리오에서 40건 중 C#은 2~7건, Java(옮긴 직후)는 4~14건만 성공했습니다. 나머지는 모두 E999였습니다.

```text
E999  23505: duplicate key value violates unique constraint "lot_pkey"
```

실패한 요청은 트랜잭션째 롤백되어 수량은 서로 맞았습니다. 데이터가 어긋나지는 않았지만, 현장에서 보면 **보고한 실적이 사라진 것**입니다. 단말이 다시 보내지 않으면 생산 실적이 빠집니다.

### 원인

레거시에 일부러 넣어 둔 [`MAX + 1` 채번](01-legacy.md#max--1-채번-문제)입니다.

```text
보고 A  SELECT MAX(순번) + 1  → 0016
보고 B  SELECT MAX(순번) + 1  → 0016    A가 아직 커밋 전이라 A의 LOT이 보이지 않음
보고 A  INSERT LOT 0016, 커밋
보고 B  INSERT LOT 0016       → 중복 키 오류, 롤백
```

PostgreSQL의 기본 격리 수준(READ COMMITTED)에서는 다른 트랜잭션이 커밋하지 않은 행이 보이지 않습니다.
다음 번호를 읽고 쓰는 사이에 아무 잠금이 없어서, 동시에 들어온 보고들이 같은 번호를 계산합니다.

### 해결 방법 고르기

| 방법 | 좋은 점 | 문제 |
|---|---|---|
| DB 시퀀스 | 잠금 없이 빠름 | 공장·근무일자마다 1부터 다시 세기 어렵고, 롤백되면 번호가 비어 버림 |
| 중복 키면 다시 시도 | 코드 변경이 작음 | 몰릴수록 재시도가 늘어남. 충돌 자체는 그대로 |
| **채번 테이블 + 행 잠금** | 공장·근무일자별 순번 유지. 롤백되면 순번도 같이 롤백되어 빈 번호가 없음. DB 종류와 무관 | 같은 공장·근무일자의 채번은 한 줄로 섬 |

LOT 번호는 공장·근무일자마다 다시 세야 하고, 중간에 빈 번호가 있으면 누락된 LOT인지 헷갈리기 때문에 채번 테이블을 골랐습니다.

### 해결

[`db/init/03_lot_seq.sql`](../db/init/03_lot_seq.sql), [`ProductionMapper.xml`](../mes-api/src/main/resources/mapper/ProductionMapper.xml)

```sql
CREATE TABLE lot_seq (
    plant_cd   VARCHAR(10) NOT NULL,
    work_date  DATE        NOT NULL,
    last_seq   INT         NOT NULL,
    PRIMARY KEY (plant_cd, work_date)
);

INSERT INTO lot_seq AS s (plant_cd, work_date, last_seq)
SELECT #{plantCd}, #{workDate}, COALESCE(MAX(CAST(RIGHT(lot_no, 4) AS INT)), 0) + 1
  FROM lot WHERE lot_no LIKE #{prefix} || '%'
ON CONFLICT (plant_cd, work_date) DO UPDATE
   SET last_seq = GREATEST(s.last_seq + 1, EXCLUDED.last_seq)
RETURNING last_seq
```

- 그날 첫 LOT이면 행을 만들고, 이미 있으면 순번을 1 올립니다. 한 문장이라 "있는지 보고 만들기" 사이에 다른 보고가 끼어들 틈이 없습니다.
- 갱신한 행은 **커밋할 때까지 잠겨 있습니다.** 같은 공장·근무일자의 다른 보고는 이 문장에서 기다렸다가, 앞 보고가 커밋한 다음 번호를 받습니다.
- `GREATEST`의 두 번째 값(LOT 테이블의 `MAX + 1`)은 **전환 기간용**입니다. C# 레거시는 아직 `lot_seq`를 모르고 `MAX + 1`로 LOT을 만듭니다. 두 WAS가 같은 DB를 쓰므로 레거시가 만든 번호를 건너뛰어야 섞어 써도 겹치지 않습니다. 레거시를 걷어내면 지웁니다.

### 결과

| `lot` 시나리오 (40건 동시) | C# 레거시 | Java 옮긴 직후 | Java 수정 후 |
|---|---|---|---|
| 성공 | 2~7건 | 4~14건 | **40건** (9회 모두) |
| 실패 (E999 중복 키) | 33~38건 | 26~36건 | 0건 |

40건을 동시에 보내도 0.2~0.4초 안에 모두 처리됐습니다.
C#과 번갈아 쓰는 경우도 번호가 이어집니다: Java `…-0399` → C# `…-0400` → Java `…-0401`.

### 남은 한계: C#과 Java가 같은 순간에 쓰면

C# 20건과 Java 20건을 같은 순간에 보내면, Java도 20건 중 1~2건이 중복 키로 실패했습니다(3회 측정). 레거시가 채번 잠금을 걸지 않기 때문에 Java만 고쳐서는 막을 수 없습니다.

이건 코드가 아니라 **전환 절차**로 풉니다. nginx에서 실적 보고 경로를 Java로만 보내면(레거시로 가는 길을 닫으면) 사라집니다.
API를 하나씩 새 시스템으로 넘기는 Strangler Fig 방식에서 "동작을 고친 API는 경로째 넘긴다"는 순서가 필요한 이유입니다.

## 2. 완료된 작업지시에 실적이 계속 반영됨

### 발견

1번을 고치고 `complete` 시나리오를 다시 돌리자, 계획 수량 10인 작업지시에 **양품이 19개** 들어갔습니다(3회 모두).
1번의 E999가 이 문제를 가리고 있었습니다. 레거시 문제점 목록에도 없던 문제입니다.

LOT 채번 충돌이 섞이지 않도록 요청마다 근무일자를 다르게 해서 다시 재현했습니다.

| `complete` 시나리오 (계획 10, 30건 동시) | C# 레거시 | Java 수정 전 | Java 수정 후 |
|---|---|---|---|
| 반영된 보고 | 30건 | 18~19건 | **10건** (나머지 20건 E006) |
| 최종 양품 / 계획 | 30 / 10 | 18~19 / 10 | **10 / 10** |

C#과 Java가 같은 버그를 갖고 있었다는 것은, 레거시 동작을 그대로 옮겼다는 확인이기도 합니다.

### 원인

```text
보고 1~N   SELECT status → IN_PROGRESS        잠금 없이 읽어서 모두 통과
보고 1~N   UPDATE good_qty = good_qty + 1      같은 행이라 하나씩 차례로 실행
보고 10    누적 10 → COMPLETED, 커밋
보고 11~N  이미 "진행 중"을 읽고 통과했으므로 계속 누적
```

수량은 `good_qty = good_qty + 1`처럼 DB 안에서 더하기 때문에, 더한 수량이 사라지는 일(lost update)은 없었습니다.
문제는 **"진행 중인가"를 판단하는 시점과 수량을 더하는 시점 사이의 틈**입니다. 그 사이에 다른 보고가 작업지시를 완료시켜도, 이미 판단을 끝낸 보고는 그 사실을 모릅니다.

C#은 30건, Java는 18~19건으로 숫자가 다른 이유는 커넥션 풀 크기입니다. 동시에 상태를 읽을 수 있는 요청 수가 커넥션 수만큼이기 때문입니다.

- Java(HikariCP 기본 10개): 처음 10건이 동시에 읽고, 이 10건이 하나씩 커밋하며 돌려준 커넥션으로 9건이 더 들어와 완료 전에 상태를 읽습니다. 10 + 9 = 19
- C#(Npgsql 기본 최대 100개): 30건이 한꺼번에 읽습니다

커넥션 풀을 줄이거나 늘리면 숫자만 바뀌고 문제는 그대로입니다.

### 해결

[`WorkOrderMapper.xml`](../mes-api/src/main/resources/mapper/WorkOrderMapper.xml), [`WorkOrderService`](../mes-api/src/main/java/com/minimes/workorder/WorkOrderService.java)

```sql
SELECT ... FROM work_order WHERE wo_id = #{woId} FOR UPDATE
```

실적 보고 트랜잭션이 시작하자마자 작업지시 행을 잠급니다. 같은 작업지시의 보고는 한 줄로 서고, 다음 보고는 앞 보고가 커밋한 최신 상태(완료 여부, 누적 수량)를 보고 판단합니다.

```java
@Transactional(propagation = Propagation.MANDATORY)
public WorkOrder getWorkOrderForUpdate(long woId) { ... }
```

`FOR UPDATE` 잠금은 트랜잭션이 끝날 때 풀립니다. 트랜잭션 밖에서 부르면 SELECT가 끝나자마자 풀려서 효과가 없으므로, `MANDATORY`로 트랜잭션 안에서만 부를 수 있게 막았습니다. 밖에서 부르면 바로 예외가 납니다.

**잠금 순서**: 실적 보고는 항상 작업지시 행 → 채번 행 순서로 잠급니다. 모든 보고가 같은 순서로 잠그므로 서로 상대의 잠금을 기다리는 교착(deadlock)이 생기지 않습니다.
작업지시가 다른 보고끼리는 첫 번째 잠금이 겹치지 않고, 근무일자가 같으면 채번 행에서만 잠깐 기다립니다.

### 결과

6회 모두 10건만 반영되고 나머지 20건은 E006, 최종 양품 10 / 10이었습니다.
동시성이 없는 일반 요청은 수정 후에도 C#과 33건 모두 같았습니다. 고친 것이 다른 동작을 바꾸지 않았다는 확인입니다.

## 3. 오류 응답에 서버 경로와 SQL 노출

### 발견

1번을 재현하면서 두 WAS의 E999 응답을 비교하다가 발견했습니다. 같은 중복 키 오류인데 Java 쪽 메시지가 훨씬 길었습니다.

```text
C#    23505: duplicate key value violates unique constraint "lot_pkey"
      DETAIL: Detail redacted as it may contain sensitive data. ...

Java  ### Error updating database.  Cause: org.postgresql.util.PSQLException: ERROR: duplicate key value ...
        세부 정보: Key (lot_no)=(L-KR01-261103-0016) already exists.
      ### The error may exist in file [C:\Users\...\mes-api\build\resources\main\mapper\ProductionMapper.xml]
      ### SQL: INSERT INTO lot (lot_no, plant_cd, wo_id, item_cd, qty)   VALUES (?, ?, ?, ?, ?)
```

이 메시지는 단말 화면 알림에 그대로 뜹니다. 서버의 파일 경로, 실행한 SQL, 테이블 구조, 데이터 값이 현장 단말에 그대로 보입니다.

### 원인

예외 메시지를 그대로 응답하는 레거시 동작([레거시 문제점 6](../legacy-csharp/README.md#일부러-넣은-레거시-문제점))을 그대로 옮겼기 때문입니다.
그런데 **같은 동작을 옮겨도 라이브러리가 다르면 결과가 달랐습니다.** C#의 Npgsql은 오류 상세를 기본으로 가리지만, MyBatis는 디버깅을 돕기 위해 매퍼 파일·SQL·원인을 메시지에 모두 담습니다. 옮긴 쪽이 레거시보다 더 많이 노출하고 있었습니다.

### 해결

[`GlobalExceptionHandler`](../mes-api/src/main/java/com/minimes/common/GlobalExceptionHandler.java)

```java
@ExceptionHandler(Exception.class)
public ApiResponse<Void> handleUnexpected(Exception e) {
    String ref = UUID.randomUUID().toString().substring(0, 8);
    log.error("처리 중 예외 [ref={}]", ref, e);
    return ApiResponse.fail(ErrorCode.E999, "unexpected error (ref: " + ref + ")");
}
```

- `resultCode`(E999)와 HTTP 200은 그대로라 응답 계약은 바뀌지 않습니다. 단말 화면은 `resultCode`로 판단하고 메시지는 보여 주기만 합니다.
- 현장에서 화면에 뜬 참조 번호를 알려 주면 서버 로그에서 바로 원인을 찾을 수 있습니다.

### 결과

```text
응답       {"resultCode":"E999","message":"unexpected error (ref: 9de84644)","data":null}
서버 로그  ERROR ... GlobalExceptionHandler : 처리 중 예외 [ref=9de84644]
           org.springframework.dao.DuplicateKeyException: ...
             Key (lot_no)=(L-KR01-261103-0402) already exists.
```

## 4. localhost 요청만 2초씩 지연

### 발견

수정 전 재현 스크립트에서 요청을 모두 처리하는 데 C#은 24~84ms가 걸렸는데, Java는 매번 2,144~2,354ms가 걸렸습니다.
처리 성능 차이라기에는 실행할 때마다 거의 똑같이 2초 남짓이었습니다.

### 확인

요청을 하나씩 보내면서 주소만 바꿔 시간을 쟀습니다.

| 주소 | 응답 시간 (3회) |
|---|---|
| `localhost:8081` (Java) | 2,067 / 2,038 / 2,025 ms |
| `127.0.0.1:8081` (Java) | 3 / 3 / 4 ms |
| `localhost:5080` (C#) | 3 / 2 / 2 ms |

```text
> netstat -ano | findstr LISTENING
  TCP    127.0.0.1:5080    ...    C#
  TCP    [::1]:5080        ...    C#
  TCP    127.0.0.1:8081    ...    Java     ← [::1]:8081 이 없음
```

### 원인

- C#(ASP.NET Kestrel)은 `localhost`로 설정하면 IPv4(`127.0.0.1`)와 IPv6(`::1`) 루프백 **양쪽에서** 받습니다.
- Java(Spring Boot)의 `server.address`는 주소를 하나만 받아서 `127.0.0.1`에서만 받고 있었습니다. README에 "레거시처럼 127.0.0.1에서만 받는다"고 적었지만, 레거시는 `::1`에서도 받고 있었습니다.
- Python처럼 `localhost`의 주소를 차례로 시도하는 클라이언트는 `::1`에 먼저 연결합니다. Windows는 거부된 연결을 곧바로 실패로 돌려주지 않고 몇 차례 다시 시도해서, 실패를 확인하는 데 약 2초가 걸립니다. 그다음에야 `127.0.0.1`로 붙습니다.
- 단말 화면은 nginx를 거쳐 IPv4로 붙기 때문에 지금까지 드러나지 않았습니다.

### 해결

[`LoopbackConfig`](../mes-api/src/main/java/com/minimes/common/LoopbackConfig.java): `server.address`가 `127.0.0.1`일 때 `::1`에서 받는 Tomcat 커넥터를 하나 더 붙입니다.
IPv6를 끈 PC에서는 `::1`에 바인딩할 수 없어서 서버가 뜨지 않으므로, 그때는 건너뜁니다.

다른 방법도 검토했습니다.

- 클라이언트가 `127.0.0.1`을 쓰게 한다: 모든 클라이언트에 규칙을 강요해야 하고, "같은 주소로 부르면 레거시와 똑같이 동작한다"는 전환 원칙에 맞지 않습니다.
- 모든 주소(`0.0.0.0`, `::`)에서 받는다: 같은 네트워크의 다른 PC에 WAS가 열립니다.

### 결과

Java도 `[::1]:8081`에서 받게 되었고, `localhost:8081` 응답 시간이 2,030ms 안팎에서 4~5ms로 줄었습니다.

4단계 설비 게이트웨이(:9101)를 `127.0.0.1`에서만 열었을 때 같은 문제가 다시 나왔습니다. `localhost`로 접속하는 데 2,039ms가 걸려서, 게이트웨이도 `::1`에서 받게 하고 0.4ms로 줄였습니다([4단계 문서](04-equipment-interface.md#localhost로-접속하면-2초씩-걸리는-문제)).

아래 [개발 중 만난 문제](#개발-중-만난-문제)의 nginx 연결 문제와 같은 계열입니다. 두 번 모두 `localhost`를 주소 하나로 여긴 데서 생긴 문제였습니다.

## 5. 주소·파라미터 표기가 조금 다르면 Java만 거절

### 발견

3단계 동등성 검증을 처음 돌렸을 때 6건이 C#과 달랐습니다. 같은 원인의 다른 모양을 찾으려고 주변 요청을 더 넣어서 모두 12건을 찾았습니다.

| 요청 | C# 레거시 | Java |
|---|---|---|
| `GET /api/plants/` (끝에 슬래시) | 200 | 404 |
| `GET /API/Plants` | 200 | 404 |
| `GET /api/items?plantcd=KR01` | 0000 | E001 `plantCd is required` |
| `GET /api/items?plantCd=KR01&plantCd=US01` | 울산 품목 2건 | 빈 목록 |
| `POST /api/work-orders` `{"plantCd": 1, ...}` | 400 | E003 `item not found` |

2단계에서 손으로 90건을 비교할 때는 단말 화면이 실제로 보내는 요청 위주라, 표기를 바꿔 보는 요청은 보내지 않았습니다.

### 원인

모두 ASP.NET과 Spring의 기본 동작 차이입니다.

- ASP.NET은 주소와 쿼리 파라미터 이름의 대소문자를 가리지 않고, 주소 끝의 슬래시를 무시하고, 같은 파라미터가 여러 번 오면 첫 값을 씁니다.
- Spring은 대소문자를 구분하고, Spring 6부터는 끝 슬래시가 붙은 주소도 다른 주소로 봅니다. 같은 파라미터의 여러 값은 `"KR01,US01"`처럼 쉼표로 이어 붙입니다.
- 반대로 JSON 문자열 칸에 온 숫자는 Jackson이 `"1"`로 바꿔 받고, 레거시(System.Text.Json)는 400으로 거절합니다.

### 해결

판단 기준을 먼저 정했습니다.

1. 레거시가 받던 요청을 Java가 거절하면 맞춥니다. 그대로 두면 지금 쓰는 클라이언트가 깨집니다.
2. 레거시가 거절하던 요청을 Java가 처리해도 맞춥니다. 레거시에서 오류였던 요청으로 Java에서는 데이터가 만들어질 수 있습니다.
3. 단, HEAD·OPTIONS처럼 데이터를 읽기만 하는 HTTP 표준 동작은 막지 않고 의도한 차이로 둡니다.

설정은 [`LegacyRequestConfig`](../mes-api/src/main/java/com/minimes/common/LegacyRequestConfig.java)에 모았습니다. 끝 슬래시는 `UrlHandlerFilter`, 주소 대소문자는 `PathPatternParser`, 파라미터는 [`LegacyQueryParameterFilter`](../mes-api/src/main/java/com/minimes/common/LegacyQueryParameterFilter.java), JSON은 Jackson 변환(coercion) 설정입니다.
[`LegacyRequestConfigTest`](../mes-api/src/test/java/com/minimes/common/LegacyRequestConfigTest.java)가 C#과 DB 없이 이 설정이 빠지지 않았는지 확인합니다. 요청별 처리는 [동등성 검증 문서](03-equivalence.md#맞춘-것)에 있습니다.

### 결과

12건 중 10건을 맞췄고, HEAD·OPTIONS 2건은 이유와 함께 의도한 차이로 등록했습니다. 요청 160건 중 같음 156건, 의도한 차이 4건입니다.

## 6. 동등성 검증이 updated_at 누락을 놓침

### 발견

검증 도구가 실제로 실수를 잡는지 보려고, 전환할 때 저지를 만한 실수를 Java에 하나씩 넣고 돌렸습니다.
검증 순서, 근무조 경계, 시간대 실수는 잡았는데, 실적을 반영할 때 `updated_at`을 안 바꾸는 실수는 "통과"로 나왔습니다.

### 원인

서버 시각이 들어가는 값은 두 WAS가 몇 ms 차이로 처리해서 똑같을 수 없으므로 5초까지 같다고 봤습니다.
그런데 작업 시작과 실적 반영 사이가 약 1.7초라, 시각을 아예 안 바꿔도 5초 안에 들어왔습니다. **허용 범위가 단계 사이 간격보다 길면, 시각을 안 바꾸는 실수가 가려집니다.**

### 해결

- 허용 범위를 2초로 줄였습니다. 응답이 초 단위라 생기는 1초 차이와 두 WAS의 처리 시각 차이를 덮을 만큼만 남겼습니다.
- 시각이 바뀌어야 하는 단계 앞(상태 전이 전, 실적 반영 전)에서 3초씩 쉬게 했습니다. 이제 시각을 안 바꾸면 허용 범위보다 크게 어긋납니다.

### 결과

넣은 실수 5가지 중 4가지를 잡습니다. 남은 1가지(작업지시 행 잠금 제거)는 요청을 하나씩 보내서는 드러나지 않는 동시성 문제라 [재현 스크립트](../scripts/report_race.py)가 맡습니다([2번](#2-완료된-작업지시에-실적이-계속-반영됨)).
허용 범위를 줄인 뒤 정상 코드로 5회 연속 돌려서 모두 통과하는 것도 확인했습니다. 범위를 줄였다고 정상 코드가 `다름`으로 나오지는 않습니다.

## 개발 중 만난 문제

| 증상 | 원인 | 해결 |
|---|---|---|
| nginx → WAS 요청이 매번 한 번 실패한 뒤 성공 | `docker-compose`의 `extra_hosts: host-gateway`가 `host.docker.internal`에 IPv6 주소를 더해, localhost에서만 받는 WAS에 IPv6로 먼저 연결하다 실패하고 IPv4로 다시 연결 | Docker Desktop은 `host.docker.internal`을 IPv4로 알려 주므로 `extra_hosts`를 뺌. Linux용 설정은 주석으로 남김 |
| 같은 요청인데 C#과 Java의 응답이 다름 (5가지) | ASP.NET과 Spring의 요청 해석 방식 차이. 예: `planQty` 키가 없으면 C#은 0으로 받아 E002, Java는 record를 만들지 못해 400 | 요청 DTO를 setter 클래스로, Jackson 설정 2개 등. 전체 표는 [mes-api README](../mes-api/README.md#레거시aspnet와-동작을-맞춘-부분) |
| 없는 주소로 요청하면 404 대신 HTTP 200 + E999 | 모든 예외를 받는 `@ExceptionHandler(Exception.class)`가 Spring의 404 예외까지 받음 | `ResponseEntityExceptionHandler`를 상속해 HTTP 수준 오류는 Spring이 먼저 처리 |
| 형식이 맞는 계획일(`2026-11-03`)도 E001 | `ResolverStyle.STRICT`에서 `yyyy`는 연호(era) 기준 연도라, 연호 없이는 해석하지 못함 | 연호 없는 연도 패턴 `uuuu`로 바꿈 |
| Windows에서 빌드하면 한글 문자열이 깨짐 | Windows의 Java 17은 소스 파일을 MS949로 읽음 | `build.gradle`에서 컴파일 인코딩을 UTF-8로 고정 |
| Linux에서 `./gradlew` 실행 시 Permission denied | Windows에서 커밋하면서 실행 권한이 빠짐 | git에 실행 권한 기록 (`100644 → 100755`) |

## 남은 문제

| 문제 | 계획 |
|---|---|
| 근무조를 서버 시간대로 계산 ([레거시 문제점 2](01-legacy.md#근무조를-서버-시간으로-계산하는-문제)) | 5단계에서 공장 시간대(`plant.time_zone`) 기준으로 계산 |
| 작업지시 번호(`WO-…`)도 `MAX + 1` 채번 | 작업지시는 계획 담당자가 만들어서 동시에 몰리는 일이 드물어 이번에는 LOT만 고침. 같은 방식으로 고칠 수 있음 |
| C#과 Java가 같은 순간에 LOT을 만들면 충돌 | 실적 보고 경로를 Java로 넘기면 해결. 그 뒤 `GREATEST`의 `MAX` 조회도 지움 |
| 채번의 `MAX` 조회가 LOT 테이블 전체를 읽음 | DB 정렬 규칙(`en_US.utf8`) 때문에 `LIKE 'L-KR01-261103-%'`가 기본키 인덱스를 못 쓰고 순차 스캔. 6단계(SQL 튜닝)에서 다룸 |
| 설비가 같은 실적을 다시 보내면 두 번 반영 | 4단계(설비 TCP 인터페이스)에서 메시지 ID로 중복을 거름 |
