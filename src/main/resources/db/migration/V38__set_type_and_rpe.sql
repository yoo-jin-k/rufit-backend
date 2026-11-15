-- 세트마다 종류(일반·웜업·드롭·실패)와 운동 자각도(RPE 5~10)를 기록합니다.
-- 기존 세트는 모두 일반 세트, 자각도 미입력으로 남습니다.
-- 루틴도 같은 세트 구조를 쓰므로 함께 추가합니다(웜업 세트를 루틴에 담을 수 있게).
ALTER TABLE workout_entries
    ADD COLUMN set_type varchar(8) NOT NULL DEFAULT 'NORMAL'
        CHECK (set_type IN ('NORMAL', 'WARMUP', 'DROP', 'FAILURE')),
    ADD COLUMN rpe integer NULL CHECK (rpe IS NULL OR rpe BETWEEN 5 AND 10);

ALTER TABLE routine_entries
    ADD COLUMN set_type varchar(8) NOT NULL DEFAULT 'NORMAL'
        CHECK (set_type IN ('NORMAL', 'WARMUP', 'DROP', 'FAILURE')),
    ADD COLUMN rpe integer NULL CHECK (rpe IS NULL OR rpe BETWEEN 5 AND 10);
