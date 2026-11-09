CREATE TABLE application_metadata (
    name varchar(80) PRIMARY KEY,
    value varchar(255) NOT NULL
);
INSERT INTO application_metadata(name, value) VALUES ('application', 'rufit-backend');
