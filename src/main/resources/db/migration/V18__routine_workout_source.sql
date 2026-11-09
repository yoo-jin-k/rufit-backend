-- Historical source identity survives independent deletion of the original workout.
ALTER TABLE routines ADD COLUMN source_workout_id uuid;
ALTER TABLE routines ADD CONSTRAINT chk_routine_single_source
    CHECK (source_routine_id IS NULL OR source_workout_id IS NULL);
