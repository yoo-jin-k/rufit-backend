CREATE TABLE rest_preferences (
    owner_name varchar(100) PRIMARY KEY,
    default_rest_seconds integer NOT NULL CHECK (default_rest_seconds BETWEEN 0 AND 3600),
    version bigint NOT NULL CHECK (version > 0)
);
