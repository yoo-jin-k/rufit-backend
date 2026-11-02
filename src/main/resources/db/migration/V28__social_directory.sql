-- 검색 공개 정보는 비공개 프로필과 분리하며 기본으로 검색을 허용하지 않습니다.
CREATE TABLE social_directory (
    owner_name varchar(100) PRIMARY KEY,
    member_id uuid NOT NULL UNIQUE,
    nickname varchar(30) NOT NULL,
    discoverable boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1,
    CHECK (length(trim(nickname)) BETWEEN 2 AND 30),
    CHECK (version >= 1)
);
CREATE INDEX social_directory_search ON social_directory(lower(nickname), member_id)
    WHERE discoverable;
