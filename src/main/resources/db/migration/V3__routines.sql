CREATE TABLE routines (
 id uuid PRIMARY KEY,
 owner_name varchar(100) NOT NULL,
 title varchar(80) NOT NULL,
 version bigint NOT NULL DEFAULT 0
);
CREATE INDEX routines_owner ON routines(owner_name, id);
CREATE TABLE routine_entries (
 routine_id uuid NOT NULL REFERENCES routines(id) ON DELETE CASCADE,
 entry_order integer NOT NULL,
 exercise_name varchar(80) NOT NULL,
 weight numeric(6,2) NOT NULL CHECK(weight >= 0 AND weight <= 1500),
 reps integer NOT NULL CHECK(reps >= 1 AND reps <= 1000),
 PRIMARY KEY(routine_id, entry_order)
);
