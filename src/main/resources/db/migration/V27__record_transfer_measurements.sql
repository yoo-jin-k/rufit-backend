ALTER TABLE workouts ADD COLUMN record_date date;
ALTER TABLE workouts ADD COLUMN entry_mode varchar(16);
ALTER TABLE workouts ADD CONSTRAINT workout_manual_date CHECK (
  (record_date IS NULL AND entry_mode IS NULL) OR
  (record_date IS NOT NULL AND entry_mode = 'manual' AND finished_at IS NOT NULL AND started_at = finished_at AND paused_duration_ms = 0)
);
CREATE INDEX workouts_owner_record_date ON workouts(owner_name, record_date) WHERE record_date IS NOT NULL;
CREATE TABLE measurements (
  owner_name varchar(100) NOT NULL,
  kind varchar(16) NOT NULL CHECK (kind IN ('weight','strength')),
  record_date date NOT NULL CHECK (record_date >= DATE '1900-01-01'),
  version bigint NOT NULL CHECK (version > 0),
  weight numeric(5,2), bench numeric(6,2), squat numeric(6,2), deadlift numeric(6,2),
  PRIMARY KEY(owner_name,kind,record_date),
  CONSTRAINT measurement_values CHECK (
    (kind='weight' AND weight IS NOT NULL AND weight > 0 AND weight <= 700 AND bench IS NULL AND squat IS NULL AND deadlift IS NULL) OR
    (kind='strength' AND weight IS NULL AND bench IS NOT NULL AND squat IS NOT NULL AND deadlift IS NOT NULL
      AND bench > 0 AND bench <= 1000 AND squat > 0 AND squat <= 1000 AND deadlift > 0 AND deadlift <= 1000)
  )
);
