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
| ② | TCP 리스너, 실적 반영, 메시지 ID로 중복 반영 막기 | 예정 |
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

## 같은 실적을 두 번 받는 문제 (② 예정)

설비가 RSLT를 보냈는데 ACK를 받기 전에 연결이 끊기면, 설비는 MES가 반영했는지 알 수 없어서 같은 전문을 다시 보냅니다. 그대로 처리하면 실적이 두 번 들어갑니다.

- 처리한 메시지 ID를 실적과 **같은 트랜잭션**에 저장합니다. 실적이 롤백되면 메시지 ID도 남지 않습니다.
- 이미 처리한 메시지 ID가 다시 오면 다시 반영하지 않고, 처음에 보낸 ACK(같은 LOT 번호)를 그대로 돌려줍니다. 설비 입장에서는 몇 번을 보내도 결과가 같습니다(멱등성).
- NAK로 끝난 전문은 기록하지 않습니다. 원인을 고친 뒤 같은 ID로 다시 보내면 처리합니다.

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

테스트는 `mes-api/src/test/java/com/minimes/equipment/`에 있습니다. DB와 네트워크 없이 바이트만으로 확인합니다.

```bash
cd mes-api
./gradlew test --tests 'com.minimes.equipment.*'
```

| 테스트 | 건수 | 확인하는 것 |
|---|---|---|
| `FrameDecoderTest` | 14 | 1바이트씩 쪼개 받기, 세 전문 뭉쳐 받기, 뭉침과 쪼개짐 섞기, 한글 본문 길이(바이트), 쓰레기 바이트·길이 오류·끊긴 전문 뒤 재동기화, 헤더 형식 오류, UTF-8 오류 |
| `MessageBodyTest` | 6 | 항목 순서, 첫 `=`로만 나누기, 빈 값, 형식 오류, 쓰고 다시 읽기 |
| `ResultMessageTest` | 4 | REST 요청으로 바꾸기, 없는 키·모르는 키, 숫자 오류 E001, 키 대소문자 |
