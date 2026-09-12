ALTER TABLE workouts ADD COLUMN source_routine_id uuid;
-- Preserve the copied workout even if its original routine is later removed.
