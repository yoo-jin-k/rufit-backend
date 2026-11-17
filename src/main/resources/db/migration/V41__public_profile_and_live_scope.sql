-- 친구 검색에 보일 사진·한 줄 소개입니다. 비공개 프로필과 분리하고 사용자가 저장할 때만 채웁니다.
CREATE TABLE social_profiles (
 member_id uuid PRIMARY KEY REFERENCES social_directory(member_id) ON DELETE CASCADE,
 bio varchar(80) NOT NULL DEFAULT '',
 avatar bytea
);
-- 기존에 켜 둔 회원은 친구 공개(FRIENDS)로 유지합니다. 모두 공개는 직접 고른 경우에만 씁니다.
ALTER TABLE social_live_settings ADD COLUMN scope varchar(8) NOT NULL DEFAULT 'FRIENDS'
 CHECK (scope IN ('FRIENDS','PUBLIC'));
ALTER TABLE social_live_workouts ADD COLUMN volume numeric(12,1) NOT NULL DEFAULT 0
 CHECK (volume >= 0 AND volume <= 10000000);
