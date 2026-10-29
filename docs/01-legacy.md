# 1단계: 전환 대상 레거시 만들기

> 실무 레거시를 그대로 가져올 수는 없어서, 레거시 MES에서 흔히 보이는 구조와 문제를 **일부러 재현한 C# 서비스**를 먼저 만들었습니다.
> 이후 단계에서 이 서비스를 Java(Spring Boot) 3-tier로 전환합니다.

## 목표

- 전환 대상이 될 C# API와, 전환 후에도 그대로 쓸 PostgreSQL DB를 만든다.
- 전환할 때 **유지해야 할 것**(API 응답 계약)과 **고쳐야 할 것**(레거시 문제점)을 미리 정리한다.

## 구조

WEB – WAS – DB 3-tier 구조이고, 이 중 가운데 **WAS만 C#에서 Java로 바꿉니다.**

```
[WEB tier]                        [WAS tier]                          [DB tier]
단말 화면 ─▶ nginx :3000 ─┬─ /legacy → C# 레거시 :5080 ─┐
                          └─ /java   → Java      :8081 ─┴─▶ PostgreSQL :55432
```

WAS 안의 코드 구조는 이렇게 바뀝니다.

```
[C# 레거시 WAS]                       [Java WAS]
┌──────────────────────┐            ┌──────────────────┐  Controller (요청·응답)
│ MesController         │   전환 →   ├──────────────────┤  Service    (업무 규칙, 트랜잭션)
│  요청 처리 + 검증       │            ├──────────────────┤  Mapper     (SQL)
│  + 비즈니스 로직 + SQL  │            └──────────────────┘
└──────────────────────┘
```

화면과 DB는 그대로 두고 WAS만 바꿉니다. 여러 곳을 한 번에 바꾸면 문제가 생겼을 때 원인을 좁히기 어렵기 때문입니다.

### Tier와 Layer

- **Tier**: 따로 실행·배포되는 단위입니다. 이 프로젝트에서는 화면(nginx), WAS, DB입니다.
- **Layer**: 한 애플리케이션 코드 안의 역할 구분입니다. Controller / Service / Mapper(Repository)입니다.

실무에서 "3-tier 전환"이라고 하면 보통 C# 클라이언트 프로그램이 DB에 바로 붙는 2-tier(Client–Server) 구조에 WAS를 두는 작업을 말합니다.
이 프로젝트의 레거시는 이미 WAS가 있는 구조라서, **언어 전환과 WAS 내부의 Layer 분리**에 초점을 둡니다.

## DB 설계

스키마: [`db/init/01_schema.sql`](../db/init/01_schema.sql), 기초 데이터: [`db/init/02_seed.sql`](../db/init/02_seed.sql)

| 구분 | 테이블 | 설명 |
|---|---|---|
| 기준정보 | `plant`, `item`, `process`, `routing`, `equipment`, `defect_code` | 무엇을, 어디서, 어떤 순서로 만드는지. 거의 바뀌지 않음 |
| 실행 데이터 | `work_order`, `lot`, `production_result` | 실제로 무엇을 얼마나 만들었는지. 매일 쌓임 |

### 설계 결정

1. **모든 업무 테이블에 `plant_cd`를 둔다.**
   같은 품목 코드도 공장별로 따로 관리합니다(`PRIMARY KEY (plant_cd, item_cd)`). 공장이 늘어나도 코드 변경 없이 데이터만 추가하면 됩니다.

2. **시각은 `TIMESTAMPTZ`로 저장한다.**
   절대 시점(UTC)으로 저장하고, 어느 시간대로 보여줄지는 조회할 때 정합니다. 서울 09:00과 시카고 전날 19:00은 같은 값으로 저장됩니다.

3. **근무일자(`work_date`)와 근무조(`shift_cd`)를 따로 저장한다.**
   공장은 달력 날짜가 아니라 근무일자로 집계합니다. 새벽 2시 실적은 전날 C조(22:00~06:00) 실적입니다.

### 작업지시 상태

```
CREATED ──release──▶ RELEASED ──start──▶ IN_PROGRESS ──complete──▶ COMPLETED
                                              └─ 양품 누적이 계획 수량에 도달하면 자동 완료
```

## 실적 보고 처리 흐름

`POST /api/production-results` ([`MesController.cs`](../legacy-csharp/LegacyMes/Controllers/MesController.cs))

| 순서 | 처리 | 실패 시 |
|---|---|---|
| 1 | 필수값, 수량 검증 | E001, E002 |
| 2 | 보고 시각 결정 (없으면 서버 현재 시각) | |
| 3 | **트랜잭션 시작** | |
| 4 | 작업지시 존재 여부, `IN_PROGRESS` 여부 | E004, E006 |
| 5 | 해당 공장·공정에 설비가 있는지 | E009 |
| 6 | 불량 코드 검증 | E007, E008 |
| 7 | 근무조·근무일자 계산 | |
| 8 | 양품이 있으면 LOT 채번·저장 | |
| 9 | 실적 저장 | |
| 10 | 작업지시 양품·불량 수량 누적 | |
| 11 | 계획 수량 도달 시 자동 완료 | |
| 12 | **커밋** | |

8~11은 하나라도 실패하면 전부 롤백되어야 합니다. 실적은 저장됐는데 작업지시 수량이 안 바뀌는 식의 불일치를 막기 위해서입니다.

## 전환 후에도 유지할 것: 응답 계약

```json
{ "resultCode": "0000", "message": "OK", "data": { "wo_id": 1, "wo_no": "WO-KR01-20261029-001" } }
{ "resultCode": "E005", "message": "invalid status transition: CREATED -> IN_PROGRESS", "data": null }
```

- 성공·실패 모두 **HTTP 200**이고 `resultCode`로 구분합니다.
- `data`의 키는 DB 컬럼명 그대로 **snake_case**입니다.

REST 관례와는 다르지만, 현장 단말 화면이 이미 이 형식에 맞춰 만들어져 있다고 가정합니다. 이 프로젝트의 [단말 화면](../web/)이 바로 그 클라이언트입니다. 백엔드 언어를 바꾸면서 응답 형식까지 바뀌면 단말이 전부 깨지므로, **Java로 전환해도 에러 코드와 키 이름까지 똑같이 맞춥니다.** 형식 변경은 클라이언트와 합의해서 별도로 진행할 일입니다.

## 전환하면서 고칠 것: 레거시 문제점

전체 목록은 [`legacy-csharp/README.md`](../legacy-csharp/README.md#일부러-넣은-레거시-문제점)에 있습니다. 그중 핵심 두 가지입니다.

### 근무조를 서버 시간으로 계산하는 문제

```
앨라배마 공장(US01)에서 현지 시각 08:00에 실적 보고 → 기대값: A조
        │  "2026-10-29T08:00:00-05:00"
        ▼
서버(한국)에서 DateTime.Parse → 서버 로컬 시간 22:00으로 변환
        ▼
h = 22 → C조로 기록됨 ❌
```

울산 공장만 쓸 때는 서버도 한국에 있어서 드러나지 않는 버그입니다. 해외 공장에 전개하면서 처음 드러납니다. 근무조는 서버 시간이 아니라 **공장 시간대(`plant.time_zone`)** 기준으로 계산해야 합니다.

### `MAX + 1` 채번 문제

```sql
SELECT COALESCE(MAX(CAST(RIGHT(lot_no, 4) AS INT)), 0) + 1 FROM lot WHERE lot_no LIKE 'L-KR01-261029-%'
```

두 단말이 동시에 실적을 보내면 둘 다 같은 다음 번호를 계산하고, 늦게 들어간 쪽은 중복 키 오류로 실패합니다. 채번 테이블과 행 잠금, 또는 시퀀스로 바꿔야 합니다.

## 다음 단계로 넘기는 판단: 옮기기와 고치기를 나눈다

위 문제들을 Java로 옮기면서 한꺼번에 고치지 않습니다.

1. **2단계**: 레거시 동작을 **그대로** Java로 옮긴다. (버그도 그대로)
2. **3단계**: 같은 시나리오를 C#과 Java에 실행해서 결과가 같은지 확인한다.
3. **5단계 이후**: 동작이 바뀌는 수정(시간대, 채번)을 하나씩 적용한다.

옮기면서 동시에 고치면, 결과가 달라졌을 때 그게 전환 실수인지 의도한 수정인지 구분할 수 없기 때문입니다.

## 실행해서 확인하기

```bash
docker compose up -d
cd legacy-csharp/LegacyMes
dotnet run                                   # http://localhost:5080
```

브라우저에서 http://localhost:3000 을 열면 [단말 화면](../web/README.md)으로 같은 흐름을 직접 해 볼 수 있습니다.
화면 없이 API만 호출하려면 [`legacy-csharp/legacy.http`](../legacy-csharp/legacy.http)의 요청을 순서대로 실행하고, DB에서 결과를 확인합니다.

```bash
docker exec -it mini-mes-postgres psql -U mes -d mes
SELECT result_id, work_date, shift_cd, reported_at FROM production_result;
```
