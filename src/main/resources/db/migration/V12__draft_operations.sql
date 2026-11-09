CREATE TABLE workout_draft_operations (
 workout_id uuid NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
 operation_id uuid NOT NULL,
 applied_version bigint NOT NULL,
 applied_at timestamptz NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(workout_id,operation_id)
);
