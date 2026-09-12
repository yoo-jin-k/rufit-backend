CREATE INDEX idx_workouts_owner_started_completed
    ON workouts(owner_name, started_at DESC, id DESC)
    WHERE finished_at IS NOT NULL;
