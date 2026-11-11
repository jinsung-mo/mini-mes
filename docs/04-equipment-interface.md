# 4단계: 설비 TCP 인터페이스

설비(PLC)가 작업을 마칠 때마다 MES로 실적을 직접 보내는 경로를 만듭니다.
설비가 보낸 실적도 단말 화면(REST)과 같은 `ProductionService.report`를 거칩니다. 그래서 검증 규칙, LOT 채번, 작업지시 잠금이 두 경로에서 같습니다.

```
[PLC 시뮬레이터] ──TCP──▶ [설비 게이트웨이 (mes-api 안)] ──▶ ProductionService.report ──▶ DB
                 ◀─ACK/NAK─┘     전문 해석 · 중복 확인              (REST와 같은 규칙)
[단말 화면] ──HTTP──▶ [ProductionController] ────────────────────┘
```

| 진행 | 내용 | 상태 |
|---|---|---|
| ① | 전문 규격, 전문을 바이트로 만들고 읽는 코드, 단위 테스트 | 완료 |
| ② | TCP 리스너, 실적 반영, 메시지 ID로 중복 반영 막기 | 완료 |
| ③ | PLC 시뮬레이터, 장애 시나리오 실행과 결과 정리 | 예정 |

## 왜 전문 규격부터 정하나

HTTP는 요청 하나에 응답 하나이고, 메시지가 어디서 끝나는지 프로토콜이 알려 줍니다. TCP는 끝없이 이어지는 바이트 흐름일 뿐이라 메시지 경계가 없습니다.

- **뭉침**: 설비가 전문 두 개를 따로 보내도, 서버는 한 번의 읽기로 두 개를 붙여서 받을 수 있습니다.
- **쪼개짐**: 전문 하나가 여러 번에 나눠 읽힐 수 있습니다. 한 번 읽은 바이트가 전문 하나라고 가정하면 안 됩니다.

그래서 보내는 쪽과 받는 쪽이 "어디서 시작해서 어디서 끝나는지"를 바이트 단위로 약속해야 합니다. 이 약속이 전문 규격입니다.

## 전문 형식

```
STX | 전문 종류 | 메시지 ID | 본문 길이 | 본문       | ETX
1     4           20          4           0~9999       1     (바이트)
```

| 칸 | 길이 | 형식 | 예 |
|---|---|---|---|
| STX | 1 | `0x02` | |
| 전문 종류 | 4 | 대문자 ASCII, 짧으면 뒤를 공백으로 채움 | `RSLT`, `HBT␣` |
| 메시지 ID | 20 | `A-Z a-z 0-9 . _ -`, 뒤를 공백으로 채움 | `EQ-BODY-01-000001␣␣␣` |
| 본문 길이 | 4 | 10진수 ASCII, 앞을 0으로 채움. **UTF-8 바이트 수** | `0058` |
| 본문 | 0~9999 | UTF-8, `key=value\|key=value` | `woId=15\|procCd=BODY\|...` |
| ETX | 1 | `0x03` | |

- 헤더(전문 종류, 메시지 ID, 본문 길이)는 28바이트 고정입니다. 본문이 없는 전문은 30바이트입니다.
- 본문 길이는 글자 수가 아니라 바이트 수입니다. `message=작업지시 없음`은 15자지만 27바이트입니다(한글 한 글자가 3바이트). 가장 틀리기 쉬운 부분이라 테스트로 고정했습니다.
- 본문에는 제어 문자(STX, ETX 포함)를 넣을 수 없습니다. 그래서 프레임 중간에 STX가 보이면, 그 앞의 프레임이 중간에 끊긴 것으로 판단할 수 있습니다.

### 전문 종류

| 종류 | 방향 | 본문 | 의미 |
|---|---|---|---|
| `RSLT` | 설비 → MES | 실적 | 생산실적 보고 |
| `HBT` | 설비 → MES | 없음 | 연결이 살아 있음을 알림 |
| `ACK` | MES → 설비 | 처리 결과 (`HBT`의 응답은 없음) | 처리함 |
| `NAK` | MES → 설비 | `code`, `message` | 처리하지 않음 |

응답(`ACK`, `NAK`)은 요청과 **같은 메시지 ID**를 씁니다. 설비는 이 ID로 어느 요청의 응답인지 압니다.

### 본문 규칙

- 항목은 `|`로 나누고, 항목 안에서는 첫 번째 `=`로 키와 값을 나눕니다. 값에는 `=`가 들어가도 되지만 `|`는 안 됩니다.
- 빈 값(`defectCd=`)은 넣을 수 있습니다. 같은 키가 두 번 나오면 형식 오류입니다. 어느 값을 믿을지 정할 수 없기 때문입니다.
- 키 이름은 REST와 같습니다. 요청은 REST 요청 JSON의 키(`woId`), 응답은 REST 응답 `data`의 키(`result_id`)를 그대로 씁니다. REST 문서 하나로 두 인터페이스를 이해할 수 있게 하려는 것입니다.

**RSLT 본문** (REST `POST /api/production-results`와 같은 항목)

```
woId=15|procCd=BODY|eqpCd=EQ-BODY-01|goodQty=1|defectQty=0|defectCd=|reportedAt=2026-11-09T08:00:00+09:00
```

**ACK 본문** (REST 응답 `data`와 같은 항목)

```
result_id=101|lot_no=L-KR01-261109-0001|work_date=2026-11-09|shift_cd=A|wo_good_qty=3|wo_status=IN_PROGRESS
```

**NAK 본문**

```
code=E006|message=work order is not in progress
```

`code`는 REST의 `resultCode`와 같은 에러 코드(E001~E009, E999)입니다.

### REST와 같은 점, 다른 점

| | REST | TCP |
|---|---|---|
| 업무 검증과 검증 순서 | `ProductionService.report` | 같음 |
| 키가 없을 때 | 숫자 0, 문자열 null → Service가 E001, E002 | 같음 |
| 모르는 키 | 무시 | 같음 |
| 숫자 칸에 `10.5`, `abc` | HTTP 400 | NAK `E001`. TCP에는 HTTP 상태가 없음 |
| 키 이름 대소문자 | 무시 (레거시 ASP.NET 동작) | 구분. 새 인터페이스라 레거시를 따를 이유가 없음 |

## 잘못된 바이트를 받았을 때

깨진 전문 하나 때문에 연결 전체를 끊지 않습니다. 그 전문만 버리고 **다음 STX부터 다시 읽습니다**(재동기화). 메시지 ID를 읽을 수 있었으면 NAK로 알리고, 없으면 로그만 남깁니다.

| 받은 것 | 처리 | NAK |
|---|---|---|
| STX 앞에 다른 바이트 | 다음 STX까지 버림 | 없음 (메시지 ID 모름) |
| 전문 종류, 메시지 ID, 본문 길이 형식 오류 | 그 전문을 버림 | 메시지 ID를 읽었으면 `E001` |
| 본문 길이가 가리키는 자리에 ETX가 없음 | 그 전문을 버림 | `E001` |
| 프레임이 끝나기 전에 STX가 나옴 | 앞의 끊긴 전문을 바로 버림 | 메시지 ID를 읽었으면 `E001` |
| 본문이 UTF-8이 아님 | 그 전문을 버림 | `E001` |

"프레임이 끝나기 전에 STX"를 따로 보는 이유: 설비가 전문을 보내다 멈추고 처음부터 다시 보내면, 끊긴 전문의 본문 길이를 믿고 기다리는 동안 새로 온 정상 전문까지 붙잡히게 됩니다. 본문에 STX가 올 수 없다는 규칙 덕분에, 기다리지 않고 바로 버릴 수 있습니다.

## 게이트웨이 구조

```
accept 스레드 1개 ── 새 연결 ──▶ 연결 스레드 (최대 50개, 넘치면 새 연결을 바로 닫음)
                                  read() → FrameDecoder → 전문마다 FrameHandler → write(ACK/NAK)
```

- `accept()`와 `read()`는 데이터가 올 때까지 멈추므로, accept 스레드를 따로 두고 연결마다 스레드를 줍니다. 연결 스레드가 자기 연결의 `FrameDecoder`를 가집니다.
- 한 연결 안에서는 받은 순서대로 하나씩 처리하고 응답합니다. 같은 설비의 실적 순서가 바뀌지 않습니다.
- 30초 동안 아무것도 오지 않으면 연결을 닫습니다(`setSoTimeout`). 설비 전원이 갑자기 꺼지면 TCP가 끊김을 알려 주지 못해서, 스레드가 `read()`에서 영원히 기다릴 수 있기 때문입니다. 설비는 보낼 실적이 없어도 10초마다 `HBT`를 보냅니다.
- 게이트웨이(`EquipmentGateway`)는 소켓과 전문 경계만, 처리기(`EquipmentFrameHandler`)는 전문의 의미만 다룹니다. 그래서 게이트웨이는 DB 없이 가짜 처리기로 테스트합니다.
- Spring이 시작할 때 포트를 열고 종료할 때 닫습니다(`SmartLifecycle`). 포트를 열 수 없으면 WAS 시작이 실패합니다.
- 연결이 수천 개가 되면 연결마다 스레드를 두는 방식은 맞지 않습니다. 그때는 적은 스레드로 많은 연결을 다루는 NIO(Netty)를 씁니다. 이 프로젝트는 설비 수십 대 규모라 동작 원리가 바로 보이는 방식을 골랐습니다.

| 설정 (`mes.equipment`) | 값 | |
|---|---|---|
| `port` | 9101 | |
| `bind-address` | 127.0.0.1 | WAS처럼 루프백에서만 받음 |
| `read-timeout` | 30s | |
| `max-connections` | 50 | |

## 같은 실적을 두 번 받는 문제

설비가 RSLT를 보냈는데 ACK를 받기 전에 연결이 끊기면, 설비는 MES가 반영했는지 알 수 없어서 같은 전문을 다시 보냅니다. 그대로 처리하면 실적이 두 번 들어갑니다.

- 처리한 메시지 ID를 실적과 **같은 트랜잭션**에 저장합니다(`equipment_message`). 실적이 롤백되면 메시지 ID도 남지 않습니다.
- 이미 처리한 메시지 ID가 다시 오면 다시 반영하지 않고, 처음에 보낸 ACK(같은 LOT 번호)를 그대로 돌려줍니다. 설비 입장에서는 몇 번을 보내도 결과가 같습니다(멱등성).
- 같은 메시지 ID로 **다른 내용**이 오면 반영하지 않고 NAK `E001`을 보냅니다. 어느 쪽을 믿을지 정할 수 없기 때문입니다.
- NAK로 끝난 전문은 기록하지 않습니다. 원인을 고친 뒤 같은 ID로 다시 보내면 처리합니다.
- 키는 (작업지시, 설비, 메시지 ID)입니다. 설비 코드는 공장마다 같은 값이 있어서(`EQ-BODY-01`이 세 공장에 모두 있음) 작업지시로 공장을 구분합니다.

처리 순서(`EquipmentResultService.report`, 하나의 트랜잭션):

```
1. 본문 해석                     형식 오류 → E001
2. 작업지시 행 잠금               같은 작업지시의 전문이 한 줄로 섬
3. 처리한 메시지 ID인가?          그렇다면 저장한 ACK 반환 (업무 검증보다 먼저)
4. ProductionService.report      REST와 같은 검증(E001~E009), 채번, 수량 누적
5. 메시지 ID와 ACK 본문 저장      기본키(UNIQUE)가 마지막 방어선
```

순서를 이렇게 정한 이유는 두 가지입니다.

- **중복 확인이 업무 검증보다 먼저여야 합니다.** 마지막 실적으로 작업지시가 완료된 뒤 그 실적이 다시 오면, 상태 검사에서 E006(진행 중 아님)이 나갑니다. 설비는 실패한 줄 알고 계속 다시 보냅니다.
- **잠근 다음에 확인해야 합니다.** 확인만 하고 잠그지 않으면, 동시에 온 재전송들이 모두 "처리한 적 없음"을 보고 함께 진행합니다(트러블 슈팅 1의 LOT 번호 중복과 같은 종류의 문제).
- 단, 작업지시가 없거나 `woId`, `eqpCd`가 비어 있으면 처리된 적이 있을 수 없으므로 잠금과 중복 확인을 건너뜁니다. 그래서 검증 순서가 REST와 같게 유지됩니다(E001 → E002 → E004). 기존 `getWorkOrderForUpdate`는 없으면 E004를 던져서 이 순서를 깨므로, 없으면 null을 돌려주는 `findWorkOrderForUpdate`를 추가했습니다.

### 잠금이 정말 필요한지 확인하기

10개 연결이 같은 전문을 동시에 보내는 테스트에서, 잠금을 일부러 빼고(`findWorkOrderForUpdate` 대신 잠그지 않는 `getWorkOrder`) 돌려 봤습니다.

| | 잠금 있음 | 잠금 없음 |
|---|---|---|
| 테스트 | 통과 | 실패 |
| 실적 / 누적 양품 | 1건 / 4 | 1건 / 4 |
| 설비가 받은 응답 | 10개 모두 같은 ACK (9개는 "이미 처리한 전문" 로그) | 1개만 ACK, 나머지는 `equipment_message_pkey` 중복 키로 롤백되어 E999 |

잠금이 없어도 기본키 덕분에 **데이터**는 지켜졌습니다. 하지만 나머지 설비는 E999를 받고 다시 보내야 합니다. 그래서 잠금은 정상 응답을, 기본키는 데이터를 지키는 역할로 둘 다 둡니다.

## 설계 결정

| 결정 | 고른 것 | 고르지 않은 것과 이유 |
|---|---|---|
| 프레임 경계 | STX/ETX + 본문 길이 | 길이만 쓰면 길이가 한 번 틀렸을 때 다시 맞출 기준이 없음. STX/ETX만 쓰면 본문에 ETX가 들어가는지 매번 검사해야 함. 둘 다 쓰면 길이로 자르고 ETX로 확인하고, 깨지면 STX로 다시 맞춤 |
| 헤더 | ASCII 고정 길이 | 바이너리 정수는 바이트 순서(엔디언)를 따로 약속해야 하고, 패킷 캡처를 사람이 바로 읽기 어려움 |
| 본문 | `key=value` 가변 길이 | 고정 길이 본문은 설비 전문에서 흔하지만, 항목이 하나 늘 때마다 모든 설비의 코드를 바꿔야 함. 이 프로젝트는 REST와 같은 항목을 쓰는 것이 더 중요함 |
| 전문 해석 위치 | 연결마다 디코더 하나 (`FrameDecoder`) | 버퍼(상태)가 있어서 스레드 사이에 공유하면 안 됨 |

## 코드

| 파일 | 역할 |
|---|---|
| [`equipment/protocol/Frame.java`](../mes-api/src/main/java/com/minimes/equipment/protocol/Frame.java) | 전문 하나 (종류, 메시지 ID, 본문) |
| [`equipment/protocol/FrameFormat.java`](../mes-api/src/main/java/com/minimes/equipment/protocol/FrameFormat.java) | 바이트 형식 상수 |
| [`equipment/protocol/FrameEncoder.java`](../mes-api/src/main/java/com/minimes/equipment/protocol/FrameEncoder.java) | 전문 → 바이트 |
| [`equipment/protocol/FrameDecoder.java`](../mes-api/src/main/java/com/minimes/equipment/protocol/FrameDecoder.java) | 바이트 → 전문. 뭉침, 쪼개짐, 재동기화 |
| [`equipment/protocol/MessageBody.java`](../mes-api/src/main/java/com/minimes/equipment/protocol/MessageBody.java) | 본문 `key=value` 읽기·쓰기 |
| [`equipment/ResultMessage.java`](../mes-api/src/main/java/com/minimes/equipment/ResultMessage.java) | RSLT 본문 → REST와 같은 `ReportResultRequest` |
| [`equipment/EquipmentGateway.java`](../mes-api/src/main/java/com/minimes/equipment/EquipmentGateway.java) | TCP 리스너, 연결 스레드, 타임아웃, 시작·종료 |
| [`equipment/EquipmentFrameHandler.java`](../mes-api/src/main/java/com/minimes/equipment/EquipmentFrameHandler.java) | 전문 종류별 처리, ACK/NAK 만들기 |
| [`equipment/EquipmentResultService.java`](../mes-api/src/main/java/com/minimes/equipment/EquipmentResultService.java) | 잠금 → 중복 확인 → 실적 처리 → 기록 (한 트랜잭션) |
| [`db/init/04_equipment_message.sql`](../db/init/04_equipment_message.sql) | 처리한 메시지 ID와 ACK 본문 |

이미 만들어진 DB에는 테이블을 직접 추가합니다(여러 번 실행해도 안전).

```bash
docker exec -i mini-mes-postgres psql -U mes -d mes < db/init/04_equipment_message.sql
```

테스트는 `mes-api/src/test/java/com/minimes/equipment/`에 있습니다.

```bash
cd mes-api
./gradlew test      # DB 없이: 전문 해석, 실제 소켓으로 게이트웨이
./gradlew dbTest    # DB 컨테이너 필요: 소켓부터 DB까지
```

`dbTest`는 테스트용 DB(`mes_equipment_test`)를 새로 만들어 `db/init` 스크립트를 실행한 뒤 씁니다. 동등성 검증과 같은 방법이라 개발 DB(`mes`)의 작업지시는 바뀌지 않습니다.

| 테스트 | 건수 | 확인하는 것 |
|---|---|---|
| `FrameDecoderTest` | 14 | 1바이트씩 쪼개 받기, 세 전문 뭉쳐 받기, 뭉침과 쪼개짐 섞기, 한글 본문 길이(바이트), 쓰레기 바이트·길이 오류·끊긴 전문 뒤 재동기화, 헤더 형식 오류, UTF-8 오류 |
| `MessageBodyTest` | 6 | 항목 순서, 첫 `=`로만 나누기, 빈 값, 형식 오류, 쓰고 다시 읽기 |
| `ResultMessageTest` | 4 | REST 요청으로 바꾸기, 없는 키·모르는 키, 숫자 오류 E001, 키 대소문자 |
| `EquipmentGatewayTest` | 8 | 실제 소켓으로: 같은 메시지 ID로 응답, 1바이트씩 보내기, 세 전문 뭉쳐 보내기, 깨진 전문 NAK 뒤 연결 유지, 읽기 타임아웃, HBT로 연결 유지, 최대 연결 수 초과, 종료 시 연결 닫기 |
| `EquipmentResultDbTest` (dbTest) | 7 | REST와 같은 ACK 항목, 재전송, 완료 뒤 재전송, 같은 ID 다른 내용, 설비가 다르면 다른 실적, 동시 재전송 10개, 검증 순서와 에러 코드 |

## 남은 일 (③)

- PLC 시뮬레이터(`scripts/plc_simulator.py`)로 장애 시나리오(쪼개 보내기, ACK를 잃고 재전송, 연결 끊김 뒤 재접속)를 실행하고 결과를 정리합니다.
- 게이트웨이는 `127.0.0.1`(IPv4)에서만 받습니다. 설비 쪽에서 `localhost`로 접속하면 IPv6(`::1`)를 먼저 시도하다 늦어질 수 있습니다([트러블 슈팅 4](troubleshooting.md#4-localhost-요청만-2초씩-지연)와 같은 원인). 실제 설비는 IP로 접속하므로 시뮬레이터도 `127.0.0.1`을 쓰고, 영향이 있는지는 ③에서 측정해서 정합니다.
