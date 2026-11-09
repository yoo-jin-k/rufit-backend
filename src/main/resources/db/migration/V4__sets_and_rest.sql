ALTER TABLE workouts ADD COLUMN rest_ends_at timestamptz;
ALTER TABLE workouts ADD COLUMN rest_set_index integer;
CREATE TABLE completed_workout_sets (
 workout_id uuid NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
 set_index integer NOT NULL CHECK(set_index >= 0 AND set_index < 300),
 PRIMARY KEY(workout_id, set_index)
);
