# Mini MES: C# → Java 전환 프로젝트

완성차 공장의 MES(제조실행시스템)를 작게 만들고, **C# 레거시 서비스를 Java(Spring Boot) 3-tier 서비스로 전환**하는 과정을 직접 해 보는 학습 프로젝트입니다.

## 왜 만드나

완성차 MES 백엔드 직무에서 하는 일을 작은 규모로 경험해 보려고 합니다.

| 직무에서 하는 일 | 이 프로젝트에서 하는 것 |
|---|---|
| 기존 서비스의 개발 언어 전환 (C# → Java) | C# 레거시 API를 같은 API 계약으로 Spring Boot에 재구현 |
| MSA, 3-Tier 아키텍처 반영 | Controller / Service / Repository 분리, 서비스 분리 |
| 그룹표준 MES Framework | 공통 응답·예외·공장 컨텍스트를 `mes-common` 모듈로 분리 |
| 서버/단말 데이터 인터페이스 (Socket, REST, EAI) | 가짜 PLC 시뮬레이터 → TCP 게이트웨이 → 실적 반영 |
| 해외 공장 전개 | 공장 코드·시간대 기반 근무조/근무일자 계산 |
| SQL 튜닝, 성능점검 | 대용량 실적 데이터 집계 쿼리 `EXPLAIN ANALYZE` 전후 비교 |
| CI/CD | GitLab CI 스타일 파이프라인, Docker 이미지 |

## 도메인

- **기준정보**: 공장, 품목, 공정(차체 → 도장 → 의장 → 검사), 라우팅, 설비, 불량 코드
- **작업지시**: `CREATED → RELEASED → IN_PROGRESS → COMPLETED`
- **생산실적**: 양품/불량 보고, LOT 발행, 근무조(A 06~14, B 14~22, C 22~06) 계산, 계획 수량 도달 시 자동 완료
- **공장**: 울산(KR01, Asia/Seoul), 앨라배마(US01, America/Chicago), 체코(CZ01, Europe/Prague)

## 진행 상황

- [x] **1단계: C# 레거시 API** — [`legacy-csharp/`](legacy-csharp/) (일부러 넣은 레거시 문제점 정리 포함)
- [ ] 2단계: Spring Boot 3-tier 전환 (API 계약 유지)
- [ ] 3단계: 동등성 검증 (같은 시나리오를 C#/Java 양쪽에 실행해 결과 비교)
- [ ] 4단계: 설비 TCP 인터페이스 + PLC 시뮬레이터
- [ ] 5단계: 해외 공장 시간대 처리 (레거시 버그 수정)
- [ ] 6단계: SQL 튜닝
- [ ] 7단계: CI/CD, 컨테이너 배포

## 실행

```bash
docker compose up -d          # PostgreSQL 16 (localhost:55432, mes/mes)
```

각 모듈 실행 방법은 모듈별 README를 참고하세요.

## 기술 스택

- Legacy: C# / .NET 8 / ASP.NET Core / Npgsql
- Target: Java 17 / Spring Boot 3
- DB: PostgreSQL 16
