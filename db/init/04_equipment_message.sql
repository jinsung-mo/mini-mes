-- 설비가 보낸 실적 전문의 처리 기록 (4단계: 설비 TCP 인터페이스)
-- 설비는 ACK를 받지 못하면 같은 메시지 ID로 다시 보낸다. 이미 처리한 메시지 ID가 다시 오면
-- 실적을 다시 반영하지 않고, 여기 저장한 ACK 본문을 그대로 돌려준다.
--
-- 실적 저장과 같은 트랜잭션에서 INSERT한다. 실적이 롤백되면 이 기록도 남지 않는다.
-- 키에 작업지시를 넣는 이유: 설비 코드는 공장마다 같은 값이 있다 (EQ-BODY-01이 KR01, US01, CZ01에 모두 있음).
-- 작업지시가 공장을 정하므로 (작업지시, 설비, 메시지 ID)면 어느 공장의 어느 설비가 보낸 전문인지 구분된다.
-- 기본키(UNIQUE)는 코드의 중복 확인을 빠져나간 경우의 마지막 방어선이다.
--
-- 새로 만드는 DB는 docker-entrypoint-initdb.d가 이 파일을 자동으로 실행한다.
-- 이미 만들어진 DB에는 직접 적용한다 (여러 번 실행해도 안전):
--   docker exec -i mini-mes-postgres psql -U mes -d mes < db/init/04_equipment_message.sql
CREATE TABLE IF NOT EXISTS equipment_message (
    wo_id         BIGINT        NOT NULL REFERENCES work_order (wo_id),
    eqp_cd        VARCHAR(20)   NOT NULL,
    message_id    VARCHAR(20)   NOT NULL,
    result_id     BIGINT        NOT NULL REFERENCES production_result (result_id),
    request_body  VARCHAR(9999) NOT NULL,          -- 같은 ID로 다른 내용을 보냈는지 확인용
    ack_body      VARCHAR(9999) NOT NULL,          -- 재전송 때 그대로 돌려줄 ACK 본문
    received_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (wo_id, eqp_cd, message_id)
);
