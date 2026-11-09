CREATE INDEX workouts_owner_finished ON workouts(owner_name, finished_at DESC, id DESC)
WHERE finished_at IS NOT NULL;
