-- 스트레칭·플랭크처럼 시간으로 기록하는 종목을 추가합니다.
-- 기존 종목은 모두 무게·횟수(WEIGHT_REPS)로 남고, 기존 세트는 횟수가 있으므로 새 검사 조건을 그대로 만족합니다.
ALTER TABLE exercises
    ADD COLUMN tracking varchar(16) NOT NULL DEFAULT 'WEIGHT_REPS'
        CHECK (tracking IN ('WEIGHT_REPS', 'DURATION'));

-- 세트 한 줄에는 횟수와 시간 중 하나만 있습니다. 시간 세트는 무게 0으로 저장합니다.
ALTER TABLE workout_entries
    ALTER COLUMN reps DROP NOT NULL,
    ADD COLUMN duration_seconds integer NULL CHECK (duration_seconds BETWEEN 1 AND 3600),
    ADD CONSTRAINT workout_entries_reps_or_duration CHECK (
        (reps IS NOT NULL AND duration_seconds IS NULL) OR
        (reps IS NULL AND duration_seconds IS NOT NULL AND weight = 0)
    );

ALTER TABLE routine_entries
    ALTER COLUMN reps DROP NOT NULL,
    ADD COLUMN duration_seconds integer NULL CHECK (duration_seconds BETWEEN 1 AND 3600),
    ADD CONSTRAINT routine_entries_reps_or_duration CHECK (
        (reps IS NOT NULL AND duration_seconds IS NULL) OR
        (reps IS NULL AND duration_seconds IS NOT NULL AND weight = 0)
    );

-- 앱에 함께 들어 있는 종목 목록(catalog-v2.json)과 같은 식별자입니다.
INSERT INTO exercises(id,name,body_part,equipment,tracking,recommended_rest_seconds) VALUES
('bcde79cc-553d-5d89-b395-62aa6d90145e','목 옆 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('d6d4f7a7-0c58-547a-9d03-4372ad808411','어깨 뒤 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('dfbd661a-71db-5b6c-b47f-48522c149ec5','삼두 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('4982b093-04b2-571d-ae0c-f0fa15690a16','가슴 문틀 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('bdde936e-2e2e-5215-a431-d459a742b4ea','광배 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('cc25a015-472a-5bb0-91a1-4373ed672cec','고양이 소 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('0bfe3733-de60-5487-b64f-d88d516ff4f6','아기 자세','STRETCH','BODYWEIGHT','DURATION',15),
('96c626c9-013d-5085-a0fc-42b9f1a01625','햄스트링 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('308d48f3-e8dd-5443-a833-253f7cd8e61a','대퇴사두 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('01c00b76-203d-5ade-8057-f1e973d085d8','종아리 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('712a89cf-8363-59d6-abc1-4d78480e8727','고관절 굴곡근 스트레칭','STRETCH','BODYWEIGHT','DURATION',15),
('10b97403-9de5-5965-bcc4-85c9d2ecaa99','비둘기 자세','STRETCH','BODYWEIGHT','DURATION',15),
('be98d421-1e9d-5d87-bd98-530361afd8e4','플랭크','CORE','BODYWEIGHT','DURATION',60),
('c9d8f18c-5b75-5688-9e5c-45cc877f5f21','사이드 플랭크','CORE','BODYWEIGHT','DURATION',60);

-- 검색어는 종목 목록(catalog-v2.json)의 이름·별칭을 정규화한 값입니다.
INSERT INTO exercise_search_aliases(exercise_id,normalized_alias) VALUES
('bcde79cc-553d-5d89-b395-62aa6d90145e','목옆스트레칭'),('bcde79cc-553d-5d89-b395-62aa6d90145e','목스트레칭'),('bcde79cc-553d-5d89-b395-62aa6d90145e','neckstretch'),
('d6d4f7a7-0c58-547a-9d03-4372ad808411','어깨뒤스트레칭'),('d6d4f7a7-0c58-547a-9d03-4372ad808411','어깨스트레칭'),('d6d4f7a7-0c58-547a-9d03-4372ad808411','shoulderstretch'),
('dfbd661a-71db-5b6c-b47f-48522c149ec5','삼두스트레칭'),('dfbd661a-71db-5b6c-b47f-48522c149ec5','tricepsstretch'),
('4982b093-04b2-571d-ae0c-f0fa15690a16','가슴문틀스트레칭'),('4982b093-04b2-571d-ae0c-f0fa15690a16','가슴스트레칭'),('4982b093-04b2-571d-ae0c-f0fa15690a16','cheststretch'),
('bdde936e-2e2e-5215-a431-d459a742b4ea','광배스트레칭'),('bdde936e-2e2e-5215-a431-d459a742b4ea','latstretch'),
('cc25a015-472a-5bb0-91a1-4373ed672cec','고양이소스트레칭'),('cc25a015-472a-5bb0-91a1-4373ed672cec','캣카우'),('cc25a015-472a-5bb0-91a1-4373ed672cec','catcow'),
('0bfe3733-de60-5487-b64f-d88d516ff4f6','아기자세'),('0bfe3733-de60-5487-b64f-d88d516ff4f6','child''spose'),
('96c626c9-013d-5085-a0fc-42b9f1a01625','햄스트링스트레칭'),('96c626c9-013d-5085-a0fc-42b9f1a01625','hamstringstretch'),
('308d48f3-e8dd-5443-a833-253f7cd8e61a','대퇴사두스트레칭'),('308d48f3-e8dd-5443-a833-253f7cd8e61a','허벅지앞스트레칭'),('308d48f3-e8dd-5443-a833-253f7cd8e61a','quadstretch'),
('01c00b76-203d-5ade-8057-f1e973d085d8','종아리스트레칭'),('01c00b76-203d-5ade-8057-f1e973d085d8','calfstretch'),
('712a89cf-8363-59d6-abc1-4d78480e8727','고관절굴곡근스트레칭'),('712a89cf-8363-59d6-abc1-4d78480e8727','장요근스트레칭'),('712a89cf-8363-59d6-abc1-4d78480e8727','hipflexorstretch'),
('10b97403-9de5-5965-bcc4-85c9d2ecaa99','비둘기자세'),('10b97403-9de5-5965-bcc4-85c9d2ecaa99','pigeonpose'),
('be98d421-1e9d-5d87-bd98-530361afd8e4','플랭크'),('be98d421-1e9d-5d87-bd98-530361afd8e4','plank'),
('c9d8f18c-5b75-5688-9e5c-45cc877f5f21','사이드플랭크'),('c9d8f18c-5b75-5688-9e5c-45cc877f5f21','sideplank');
