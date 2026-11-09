-- Every new full-snapshot save must participate in optimistic locking, including no-op snapshots.
ALTER TABLE workouts ADD COLUMN last_draft_operation_id uuid;
