-- 계정별 즐겨찾기 종목입니다. 같은 종목은 한 번만 담고, 종목이 지워지면 함께 지웁니다.
CREATE TABLE exercise_favorites (
    owner_name varchar(100) NOT NULL,
    exercise_id uuid NOT NULL REFERENCES exercises(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (owner_name, exercise_id)
);
CREATE INDEX exercise_favorites_recent ON exercise_favorites(owner_name, created_at DESC);
