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
레거시처럼 `127.0.0.1`에서만 요청을 받아서 같은 네트워크의 다른 PC에서는 접근할 수 없습니다.

## 구조: 기능별 패키지 + 계층

```
com.minimes
├─ common/   ApiResponse(응답 형식), ErrorCode, BusinessException, GlobalExceptionHandler
└─ master/   기준정보 조회
   ├─ MasterController   Presentation: HTTP 요청 ↔ 자바 객체
   ├─ MasterService      Business: 입력 검증, 업무 규칙
   ├─ MasterMapper       Data Access: SQL 호출 (SQL은 resources/mapper/MasterMapper.xml)
   └─ Plant, Item, ...   응답 DTO (record, JSON 키는 snake_case)
```

기능(master, workorder, production)별로 패키지를 나눠 두면, 나중에 MSA로 서비스를 쪼갤 때 이 경계를 그대로 쓸 수 있습니다.

### 요청 하나의 흐름

```
GET /api/items?plantCd=KR01
 → MasterController.getItems("KR01")
 → MasterService.getItems    plantCd가 비었으면 BusinessException(E001)
 → MasterMapper.findItems    MasterMapper.xml의 SQL 실행, 결과 행을 Item 레코드로 변환
 → ApiResponse.ok(items)     {"resultCode":"0000","message":"OK","data":[{"item_cd":"SEDAN-A",...}]}
```

검증에 걸리면 `GlobalExceptionHandler`가 `{"resultCode":"E001","message":"plantCd is required","data":null}`로 바꿉니다. HTTP 상태는 레거시처럼 200입니다.

## 전환 진행 상황

- [x] 기준정보 조회: plants, items, routings, equipments, defect-codes
- [ ] 작업지시: 목록, 상세, 생성, 상태 전이
- [ ] 실적 보고: LOT 발행, 근무조 계산, 자동 완료, LOT·실적 조회
