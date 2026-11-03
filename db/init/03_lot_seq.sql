-- LOT 채번 테이블 (트러블 슈팅 1: 동시 실적 보고 시 LOT 번호 중복)
-- 공장·근무일자마다 마지막으로 쓴 LOT 순번을 둔다. LOT 번호 L-{공장}-{근무일자}-{순번4}의 순번 부분이다.
-- 실적 보고 트랜잭션 안에서 이 행을 갱신하면 커밋할 때까지 행 잠금이 유지되어, 같은 범위의 채번이 한 줄로 선다.
--
-- 새로 만드는 DB는 docker-entrypoint-initdb.d가 이 파일을 자동으로 실행한다.
-- 이미 만들어진 DB에는 직접 적용한다 (여러 번 실행해도 안전):
--   docker exec -i mini-mes-postgres psql -U mes -d mes < db/init/03_lot_seq.sql
CREATE TABLE IF NOT EXISTS lot_seq (
    plant_cd    VARCHAR(10)  NOT NULL REFERENCES plant (plant_cd),
    work_date   DATE         NOT NULL,
    last_seq    INT          NOT NULL,
    PRIMARY KEY (plant_cd, work_date)
);
