ALTER TABLE workouts ADD COLUMN cancelled_at timestamptz;
ALTER TABLE workouts ADD CONSTRAINT workout_cancel_time_order
CHECK (cancelled_at IS NULL OR cancelled_at >= started_at);
ALTER TABLE workouts ADD CONSTRAINT workout_terminal_state
CHECK (finished_at IS NULL OR cancelled_at IS NULL);
DROP INDEX one_active_workout_per_owner;
CREATE UNIQUE INDEX one_active_workout_per_owner ON workouts(owner_name)
WHERE finished_at IS NULL AND cancelled_at IS NULL;
