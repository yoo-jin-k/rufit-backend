CREATE TABLE retired_workout_keys (
 id uuid PRIMARY KEY,
 owner_name varchar(100) NOT NULL,
 deleted_at timestamptz NOT NULL
);
