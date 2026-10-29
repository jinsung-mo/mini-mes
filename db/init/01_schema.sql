-- Mini MES 스키마
-- 모든 업무 테이블은 plant_cd(공장 코드)를 가진다. 해외 공장 전개를 전제로 한 설계.

CREATE TABLE plant (
    plant_cd    VARCHAR(10)  PRIMARY KEY,
    plant_nm    VARCHAR(100) NOT NULL,
    time_zone   VARCHAR(50)  NOT NULL          -- IANA 시간대 (예: Asia/Seoul)
);

CREATE TABLE item (
    plant_cd    VARCHAR(10)  NOT NULL REFERENCES plant (plant_cd),
    item_cd     VARCHAR(30)  NOT NULL,
    item_nm     VARCHAR(100) NOT NULL,
    PRIMARY KEY (plant_cd, item_cd)
);

CREATE TABLE process (
    plant_cd    VARCHAR(10)  NOT NULL REFERENCES plant (plant_cd),
    proc_cd     VARCHAR(20)  NOT NULL,
    proc_nm     VARCHAR(100) NOT NULL,
    PRIMARY KEY (plant_cd, proc_cd)
);

-- 품목별 공정 순서
CREATE TABLE routing (
    plant_cd    VARCHAR(10)  NOT NULL,
    item_cd     VARCHAR(30)  NOT NULL,
    seq         INT          NOT NULL,
    proc_cd     VARCHAR(20)  NOT NULL,
    PRIMARY KEY (plant_cd, item_cd, seq),
    FOREIGN KEY (plant_cd, item_cd) REFERENCES item (plant_cd, item_cd),
    FOREIGN KEY (plant_cd, proc_cd) REFERENCES process (plant_cd, proc_cd)
);

CREATE TABLE equipment (
    plant_cd    VARCHAR(10)  NOT NULL,
    eqp_cd      VARCHAR(20)  NOT NULL,
    eqp_nm      VARCHAR(100) NOT NULL,
    proc_cd     VARCHAR(20)  NOT NULL,
    PRIMARY KEY (plant_cd, eqp_cd),
    FOREIGN KEY (plant_cd, proc_cd) REFERENCES process (plant_cd, proc_cd)
);

CREATE TABLE defect_code (
    defect_cd   VARCHAR(10)  PRIMARY KEY,
    defect_nm   VARCHAR(100) NOT NULL
);

-- 작업지시. status: CREATED -> RELEASED -> IN_PROGRESS -> COMPLETED
CREATE TABLE work_order (
    wo_id        BIGSERIAL    PRIMARY KEY,
    plant_cd     VARCHAR(10)  NOT NULL,
    wo_no        VARCHAR(40)  NOT NULL UNIQUE,
    item_cd      VARCHAR(30)  NOT NULL,
    plan_qty     INT          NOT NULL CHECK (plan_qty > 0),
    good_qty     INT          NOT NULL DEFAULT 0,
    defect_qty   INT          NOT NULL DEFAULT 0,
    status       VARCHAR(20)  NOT NULL,
    plan_date    DATE         NOT NULL,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (plant_cd, item_cd) REFERENCES item (plant_cd, item_cd)
);

CREATE TABLE lot (
    lot_no      VARCHAR(40)  PRIMARY KEY,
    plant_cd    VARCHAR(10)  NOT NULL,
    wo_id       BIGINT       NOT NULL REFERENCES work_order (wo_id),
    item_cd     VARCHAR(30)  NOT NULL,
    qty         INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 생산실적. work_date/shift_cd는 "공장 기준" 근무일자·근무조
CREATE TABLE production_result (
    result_id   BIGSERIAL    PRIMARY KEY,
    plant_cd    VARCHAR(10)  NOT NULL,
    wo_id       BIGINT       NOT NULL REFERENCES work_order (wo_id),
    proc_cd     VARCHAR(20)  NOT NULL,
    eqp_cd      VARCHAR(20)  NOT NULL,
    lot_no      VARCHAR(40)  REFERENCES lot (lot_no),
    good_qty    INT          NOT NULL,
    defect_qty  INT          NOT NULL,
    defect_cd   VARCHAR(10)  REFERENCES defect_code (defect_cd),
    work_date   DATE         NOT NULL,
    shift_cd    CHAR(1)      NOT NULL,          -- A 06-14, B 14-22, C 22-06
    reported_at TIMESTAMPTZ  NOT NULL
);
