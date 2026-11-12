# mes-api: Java WAS (전환 결과)

C# 레거시([`legacy-csharp/`](../legacy-csharp/))를 Spring Boot로 옮기는 WAS입니다. 같은 DB를 쓰고, 같은 응답 계약을 지킵니다.

## 실행

```bash
docker compose up -d          # 레포 루트에서. PostgreSQL + nginx
cd mes-api
./gradlew bootRun             # http://localhost:8081  (PowerShell: .\gradlew.bat bootRun)
```

화면에서 쓰려면 http://localhost:3000 을 열고 상단 WAS 스위치를 Java로 바꿉니다.
IntelliJ에서는 `mes-api` 폴더를 Gradle 프로젝트로 열고 `MesApiApplication`을 실행하면 됩니다.

## 버전

| 항목 | 버전 | 고른 이유 |
|---|---|---|
| Java | 17 | 이 PC의 JDK. Spring Boot 4의 최소 버전 |
| Spring Boot | 4.0.9 | MyBatis 스타터가 공식 지원하는 범위(4.0.x). 4.1은 아직 미지원 |
| MyBatis | mybatis-spring-boot-starter 4.0.1 | 레거시 SQL을 거의 그대로 옮기기 위해 |
| Jackson | 3 (Spring Boot 4 기본) | 패키지가 `com.fasterxml.jackson`에서 `tools.jackson`으로 바뀜 |

포트는 8081입니다. 이 PC의 8080은 다른 컨테이너가 쓰고 있습니다.
레거시처럼 루프백(`127.0.0.1`, `::1`)에서만 요청을 받아서 같은 네트워크의 다른 PC에서는 접근할 수 없습니다.
`server.address`는 주소를 하나만 받기 때문에 `::1`은 `LoopbackConfig`가 따로 엽니다 ([트러블 슈팅 4](../docs/troubleshooting.md#4-localhost-요청만-2초씩-지연)).

## 구조: 기능별 패키지 + 계층

```
com.minimes
├─ common/       ApiResponse(응답 형식), ErrorCode, BusinessException, GlobalExceptionHandler, LoopbackConfig,
│                LegacyRequestConfig·LegacyQueryParameterFilter(요청 해석 규칙을 레거시에 맞춤)
├─ master/       기준정보 조회
│  ├─ MasterController   Presentation: HTTP 요청 ↔ 자바 객체
│  ├─ MasterService      Business: 입력 검증, 업무 규칙
│  ├─ MasterMapper       Data Access: SQL 호출 (SQL은 resources/mapper/MasterMapper.xml)
│  └─ Plant, Item, ...   응답 DTO (record, JSON 키는 snake_case)
├─ workorder/    작업지시: 목록·상세·생성·상태 전이
│  └─ WorkOrderStatus    상태와 전이 규칙(CREATED → RELEASED → IN_PROGRESS → COMPLETED)
├─ production/   LOT·생산실적 (주소는 /api/work-orders/{id}/lots 지만 다루는 데이터 기준으로 분리)
│  ├─ ProductionService.report   실적 보고. 하나의 트랜잭션(@Transactional)
│  ├─ WorkShift                  근무조·근무일자 계산 (DB 없이 테스트)
│  └─ ReportedAtParser           보고 시각 해석 (레거시 DateTime.TryParse와 같은 규칙)
└─ equipment/    설비 TCP 인터페이스 (4단계, 레거시에는 없는 새 인터페이스)
   ├─ protocol/                  전문 형식: Frame, FrameEncoder, FrameDecoder(뭉침·쪼개짐·재동기화), MessageBody
   ├─ EquipmentGateway           TCP 리스너(127.0.0.1·::1 :9101). 소켓과 전문 경계만 다룸
   ├─ EquipmentFrameHandler      전문 종류별 처리. REST의 Controller + 예외 처리기 자리
   └─ EquipmentResultService     잠금 → 재전송 확인 → ProductionService.report → 기록 (한 트랜잭션)
```

설비가 보낸 실적도 REST와 같은 `ProductionService.report`를 거칩니다. 전문 규격과 설계 이유는 [4단계 문서](../docs/04-equipment-interface.md)에 있습니다.

테스트: `./gradlew test`(DB 없이), `./gradlew dbTest`(DB 컨테이너 필요, 테스트용 DB를 새로 만들어 씀).

### 실적 보고: 기능 경계를 넘는 요청

`POST /api/production-results`는 세 기능에 걸쳐 있습니다. 이때도 다른 기능의 테이블을 직접 보지 않습니다.

```
ProductionService.report  (@Transactional: 아래 전부가 한 트랜잭션)
 ├─ WorkOrderService.getWorkOrderForUpdate   작업지시 행 잠금, 존재(E004), 진행 중인지(E006)
 ├─ MasterService.existsEquipment            설비(E009)
 ├─ MasterService.existsDefectCode           불량 코드(E007, E008)
 ├─ WorkShift.of(보고 시각)                   근무조·근무일자
 ├─ ProductionMapper.nextLotSeq              LOT 채번 (공장·근무일자 채번 행 잠금)
 ├─ ProductionMapper                         LOT 저장, 실적 저장
 └─ WorkOrderService.applyResult             수량 누적, 계획 수량 도달 시 자동 완료
```

잠금 순서는 항상 작업지시 행 → 채번 행입니다. 모든 보고가 같은 순서로 잠그므로 교착이 생기지 않습니다.

레거시와 다르게 고친 것 (동시 실적 보고를 재현해서 찾음, [트러블 슈팅](../docs/troubleshooting.md)):

- LOT 번호를 `MAX + 1` 대신 채번 테이블(`lot_seq`)과 행 잠금으로 땁니다 (1번)
- 실적 보고는 작업지시 행을 잠그고 시작해서, 완료된 작업지시에는 반영되지 않습니다 (2번)
- 처리 중 예외(E999)는 예외 메시지 대신 참조 번호만 응답하고, 상세는 서버 로그에 남깁니다 (3번)

레거시 동작을 그대로 옮긴 부분이라 아직 고치지 않은 것:

- 근무조를 **서버 시간대**로 계산합니다 (레거시 문제점 2 → 5단계에서 `plant.time_zone`으로)
- 작업지시 번호는 아직 `MAX + 1`로 채번합니다 (레거시 문제점 4)
- 보고 시각은 단말 화면이 보내는 ISO 형식(`2026-10-29T08:00:00-05:00`, 오프셋·초 생략, 날짜와 시각 사이 공백)만 받습니다. C# `TryParse`가 받는 `2026/10/29 8:00` 같은 형식은 E001이 됩니다

기능(master, workorder, production)별로 패키지를 나눠 두면, 나중에 MSA로 서비스를 쪼갤 때 이 경계를 그대로 쓸 수 있습니다.
그래서 작업지시가 품목 존재를 확인할 때도 품목 테이블을 직접 보지 않고 `MasterService`에 묻습니다.

### 요청 하나의 흐름

```
GET /api/items?plantCd=KR01
 → MasterController.getItems("KR01")
 → MasterService.getItems    plantCd가 비었으면 BusinessException(E001)
 → MasterMapper.findItems    MasterMapper.xml의 SQL 실행, 결과 행을 Item 레코드로 변환
 → ApiResponse.ok(items)     {"resultCode":"0000","message":"OK","data":[{"item_cd":"SEDAN-A",...}]}
```

검증에 걸리면 `GlobalExceptionHandler`가 `{"resultCode":"E001","message":"plantCd is required","data":null}`로 바꿉니다. HTTP 상태는 레거시처럼 200입니다.

## 레거시(ASP.NET)와 동작을 맞춘 부분

같은 요청을 양쪽에 보내 비교하다가 찾은 차이와, 맞춘 방법입니다. 위 5개는 2단계에서 손으로 비교하면서, 아래 5개는 [3단계 동등성 검증](../docs/03-equivalence.md#찾은-차이와-처리)으로 찾았습니다.

| 요청 | ASP.NET (레거시) | Spring 기본 | 맞춘 방법 |
|---|---|---|---|
| `planQty` 키가 없음 | 0으로 받음 → E002 | record로 받으면 400 | 요청 DTO를 record 대신 **기본 생성자 + setter 클래스**로 (`CreateWorkOrderRequest`) |
| 키가 `"PlantCd"`(대소문자 다름) | 받음 | 무시 | `spring.jackson.mapper.accept-case-insensitive-properties: true` |
| `planQty: 10.5` | 400 | 10으로 잘라서 받음 | `spring.jackson.deserialization.accept-float-as-int: false` |
| 없는 주소 | 404 | 404 | `GlobalExceptionHandler`가 `ResponseEntityExceptionHandler`를 상속해 E999로 바뀌지 않게 |
| `?status=FOO` | 빈 목록 | enum으로 받으면 400 | 조회 조건은 문자열로 받음 |
| `/api/plants/` (끝에 슬래시) | 받음 | 404 (Spring 6부터) | `UrlHandlerFilter`가 슬래시를 떼고 넘김 (`LegacyRequestConfig`) |
| `/API/Plants` | 받음 | 404 | 주소 매칭에서 대소문자 구분을 끔 (`LegacyRequestConfig`) |
| `?plantcd=KR01`, `?plantCd=KR01&plantCd=US01` | 이름 대소문자 무시, 첫 값 | 이름 구분, 값을 쉼표로 이어 붙임 | `LegacyQueryParameterFilter` |
| `"plantCd": 1` (문자열 칸에 숫자·true/false) | 400 | `"1"`로 바꿔 받음 | Jackson 변환(coercion) 설정으로 거절 (`LegacyRequestConfig`) |
| 본문도 Content-Type도 없는 POST | 415 | 400 | `GlobalExceptionHandler`에서 415 |

HEAD·OPTIONS 요청은 맞추지 않았습니다. 레거시는 405, Java는 200입니다. 데이터를 읽기만 하는 HTTP 표준 동작이라 의도한 차이로 등록했습니다.
설정이 빠지지 않았는지는 `LegacyRequestConfigTest`가 DB 없이 확인합니다.

응답 DTO는 값을 만들어 내보내기만 하므로 record를 그대로 씁니다.

## 전환 진행 상황

- [x] 기준정보 조회: plants, items, routings, equipments, defect-codes
- [x] 작업지시: 목록, 상세, 생성, 상태 전이
- [x] LOT·실적 조회 (화면의 상세 영역이 작업지시와 함께 불러오므로 먼저 옮김)
- [x] 실적 보고: LOT 발행, 근무조 계산, 자동 완료 (C#과 같은 요청 33건의 응답·DB 반영 결과가 같음)
- [x] 동등성 검증: 모든 API를 검증용 DB에서 C#과 비교. 요청 160건 중 같음 156건, 의도한 차이 4건 ([3단계](../docs/03-equivalence.md))
