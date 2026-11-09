ALTER TABLE workouts ADD COLUMN paused_at timestamptz;
ALTER TABLE workouts ADD COLUMN paused_duration_ms bigint NOT NULL DEFAULT 0;
ALTER TABLE workouts ADD CONSTRAINT workout_pause_time_order CHECK (
    paused_duration_ms >= 0 AND
    (paused_at IS NULL OR (paused_at >= started_at AND finished_at IS NULL AND cancelled_at IS NULL)) AND
    ((paused_at IS NULL AND finished_at IS NULL AND cancelled_at IS NULL) OR
     paused_duration_ms <= floor(extract(epoch FROM (coalesce(paused_at,finished_at,cancelled_at)-started_at))*1000))
);
