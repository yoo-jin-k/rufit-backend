CREATE TABLE workouts (
 id uuid PRIMARY KEY,
 owner_name varchar(100) NOT NULL,
 started_at timestamptz NOT NULL,
 finished_at timestamptz,
 version bigint NOT NULL DEFAULT 0,
 CONSTRAINT workout_time_order CHECK (finished_at IS NULL OR finished_at >= started_at)
);
CREATE INDEX workouts_owner_started ON workouts(owner_name, started_at DESC);
CREATE UNIQUE INDEX one_active_workout_per_owner ON workouts(owner_name) WHERE finished_at IS NULL;
CREATE TABLE workout_entries (
 workout_id uuid NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
 entry_order integer NOT NULL,
 exercise_name varchar(80) NOT NULL,
 weight numeric(6,2) NOT NULL CHECK(weight >= 0 AND weight <= 1500),
 reps integer NOT NULL CHECK(reps >= 1 AND reps <= 1000),
 PRIMARY KEY(workout_id, entry_order)
);
