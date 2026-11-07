-- 닉네임은 대소문자와 앞뒤 공백을 무시하고 회원마다 하나만 쓸 수 있도록 합니다.
-- 기존에 겹치는 닉네임이 있으면 회원 ID가 가장 앞선 한 명은 그대로 두고,
-- 나머지는 닉네임 뒤에 회원 ID 앞 8자리를 붙여 겹치지 않게 바꿉니다(최대 30자 유지).
WITH duplicated AS (
    SELECT owner_name,
           row_number() OVER (PARTITION BY lower(trim(nickname)) ORDER BY member_id) AS position
    FROM social_directory
)
UPDATE social_directory d
SET nickname = left(trim(d.nickname), 21) || '-' || left(d.member_id::text, 8),
    version = d.version + 1
FROM duplicated
WHERE duplicated.owner_name = d.owner_name AND duplicated.position > 1;

CREATE UNIQUE INDEX social_directory_nickname_unique ON social_directory (lower(trim(nickname)));
