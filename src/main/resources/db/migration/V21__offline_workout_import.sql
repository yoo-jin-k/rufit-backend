ALTER TABLE workouts ADD COLUMN import_fingerprint varchar(64);
ALTER TABLE workouts ADD CONSTRAINT workout_import_fingerprint_format
    CHECK (import_fingerprint IS NULL OR import_fingerprint ~ '^[0-9a-f]{64}$');
