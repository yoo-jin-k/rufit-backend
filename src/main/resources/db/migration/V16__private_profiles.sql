CREATE TABLE user_profiles (
    owner_name varchar(100) PRIMARY KEY,
    creation_key uuid NOT NULL,
    nickname varchar(30) NOT NULL,
    bio varchar(500) NOT NULL,
    experience varchar(20),
    version bigint NOT NULL,
    CHECK (experience IS NULL OR experience IN ('BEGINNER','INTERMEDIATE','ADVANCED'))
);
CREATE TABLE profile_goals (
    owner_name varchar(100) NOT NULL REFERENCES user_profiles(owner_name) ON DELETE CASCADE,
    goal varchar(30) NOT NULL CHECK (goal IN ('GENERAL_FITNESS','STRENGTH','MUSCLE_GAIN','WEIGHT_MANAGEMENT')),
    PRIMARY KEY (owner_name,goal)
);
