# legacy-csharp: 전환 대상 레거시 MES API

C# (.NET 8, ASP.NET Core)로 만든 **전환 전 레거시 서비스**입니다.
실무 레거시에서 흔히 보이는 모양을 일부러 재현했고, 이후 Java(Spring Boot)로 전환합니다.

## 실행

```bash
docker compose up -d                 # 레포 루트에서 PostgreSQL 실행 (localhost:55432)
cd legacy-csharp/LegacyMes
dotnet run                           # http://localhost:5080
```

요청 예시는 [`legacy.http`](legacy.http)에 있습니다.

## API

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/work-orders?plantCd=KR01&status=` | 작업지시 목록 |
| GET | `/api/work-orders/{id}` | 작업지시 상세 |
| POST | `/api/work-orders` | 작업지시 생성 |
| POST | `/api/work-orders/{id}/release` | CREATED → RELEASED |
| POST | `/api/work-orders/{id}/start` | RELEASED → IN_PROGRESS |
| POST | `/api/work-orders/{id}/complete` | IN_PROGRESS → COMPLETED |
| POST | `/api/production-results` | 생산실적 보고 (LOT 발행, 근무조 계산, 계획 수량 도달 시 자동 완료) |
| GET | `/api/work-orders/{id}/lots` | 발행된 LOT 목록 |
| GET | `/api/work-orders/{id}/results` | 실적 이력 |

### 응답 계약 (전환 후에도 유지해야 함)

모든 응답은 **HTTP 200**이고, 성공·실패는 `resultCode`로 구분합니다. 데이터 키는 DB 컬럼명 그대로 snake_case입니다.

```json
{ "resultCode": "0000", "message": "OK", "data": { "wo_id": 1, "wo_no": "WO-KR01-20261029-001" } }
{ "resultCode": "E005", "message": "invalid status transition: CREATED -> IN_PROGRESS", "data": null }
```

| 코드 | 의미 |
|---|---|
| 0000 | 성공 |
| E001 | 필수값 누락, 형식 오류 |
| E002 | 수량 오류 |
| E003 | 품목 없음 |
| E004 | 작업지시 없음 |
| E005 | 허용되지 않는 상태 전이 |
| E006 | 진행 중이 아닌 작업지시에 실적 보고 |
| E007 | 불량 수량이 있는데 불량 코드 없음 |
| E008 | 불량 코드 없음 |
| E009 | 해당 공장·공정의 설비 없음 |
| E999 | 처리 중 예외 |

## 일부러 넣은 레거시 문제점

전환하면서 무엇을 그대로 유지하고 무엇을 고칠지 판단하는 것이 이 프로젝트의 핵심입니다.

| # | 문제 | 위치 | 전환 시 처리 방향 |
|---|---|---|---|
| 1 | 컨트롤러 하나에 SQL, 검증, 비즈니스 로직이 모두 있음 (Fat Controller) | `MesController` | Controller / Service / Repository 3-tier로 분리 |
| 2 | 근무조·근무일자를 **서버 로컬 시간**으로 계산함 | `ReportResult` | 공장 `time_zone` 기준으로 계산. **동작이 바뀌는 수정**이므로 따로 기록 |
| 3 | 조회 시각을 `Asia/Seoul`로 고정해서 포맷함 | `WoColumns` 등 | 공장 시간대 기준으로 변경 여부 결정 (응답 계약 변경 이슈) |
| 4 | 작업지시·LOT 번호를 `MAX + 1`로 채번함. 동시 요청 시 중복 키 오류 가능 | `CreateWorkOrder`, `ReportResult` | 채번 테이블 + 행 잠금, 또는 시퀀스 |
| 5 | 상태값이 문자열 리터럴로 흩어져 있음 | 전체 | enum + 상태 전이 규칙을 한곳에 |
| 6 | 모든 예외를 `E999` + 예외 메시지로 응답 (내부 정보 노출) | `Run` | 공통 예외 처리기에서 처리, 메시지 정제 |

### 2번 문제 재현

앨라배마 공장(US01, `America/Chicago`)에서 현지 시각 08:00에 보고하면 A조여야 하지만,
서버(한국 시간)에서는 22:00이라 **C조**로 기록됩니다.

```http
POST /api/production-results
{ "woId": 2, "procCd": "BODY", "eqpCd": "EQ-BODY-01", "goodQty": 1, "defectQty": 0,
  "reportedAt": "2026-10-29T08:00:00-05:00" }

→ { "shift_cd": "C", ... }
```
