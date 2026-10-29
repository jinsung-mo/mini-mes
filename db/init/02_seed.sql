INSERT INTO plant (plant_cd, plant_nm, time_zone) VALUES
    ('KR01', '울산공장',     'Asia/Seoul'),
    ('US01', '앨라배마공장', 'America/Chicago'),
    ('CZ01', '체코공장',     'Europe/Prague');

INSERT INTO defect_code (defect_cd, defect_nm) VALUES
    ('D01', '도장 불량'),
    ('D02', '조립 불량'),
    ('D03', '외관 손상');

-- 공장마다 같은 기준정보를 가진다고 가정
INSERT INTO process (plant_cd, proc_cd, proc_nm)
SELECT p.plant_cd, v.proc_cd, v.proc_nm
FROM plant p
CROSS JOIN (VALUES
    ('BODY',  '차체'),
    ('PAINT', '도장'),
    ('ASSY',  '의장'),
    ('INSP',  '검사')
) AS v (proc_cd, proc_nm);

INSERT INTO item (plant_cd, item_cd, item_nm)
SELECT p.plant_cd, v.item_cd, v.item_nm
FROM plant p
CROSS JOIN (VALUES
    ('SEDAN-A', '중형 세단'),
    ('SUV-B',   '중형 SUV')
) AS v (item_cd, item_nm);

INSERT INTO routing (plant_cd, item_cd, seq, proc_cd)
SELECT i.plant_cd, i.item_cd, v.seq, v.proc_cd
FROM item i
CROSS JOIN (VALUES
    (10, 'BODY'),
    (20, 'PAINT'),
    (30, 'ASSY'),
    (40, 'INSP')
) AS v (seq, proc_cd);

INSERT INTO equipment (plant_cd, eqp_cd, eqp_nm, proc_cd)
SELECT p.plant_cd, v.eqp_cd, v.eqp_nm, v.proc_cd
FROM plant p
CROSS JOIN (VALUES
    ('EQ-BODY-01',  '용접 로봇 1',   'BODY'),
    ('EQ-PAINT-01', '도장 부스 1',   'PAINT'),
    ('EQ-ASSY-01',  '의장 라인 1',   'ASSY'),
    ('EQ-INSP-01',  '최종 검사대 1', 'INSP')
) AS v (eqp_cd, eqp_nm, proc_cd);
