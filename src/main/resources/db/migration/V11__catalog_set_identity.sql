ALTER TABLE workout_entries ADD COLUMN exercise_id uuid REFERENCES exercises(id);
ALTER TABLE routine_entries ADD COLUMN exercise_id uuid REFERENCES exercises(id);
CREATE INDEX workout_entries_exercise ON workout_entries(exercise_id,workout_id) WHERE exercise_id IS NOT NULL;
