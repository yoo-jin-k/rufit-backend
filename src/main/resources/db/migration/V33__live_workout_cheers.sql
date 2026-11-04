-- 공개 설정은 기본 비공개이며, 운동 상태는 기록 데이터와 분리합니다.
CREATE TABLE social_live_settings (
 member_id uuid PRIMARY KEY REFERENCES social_directory(member_id) ON DELETE CASCADE,
 sharing boolean NOT NULL DEFAULT false
);
CREATE TABLE social_live_workouts (
 member_id uuid PRIMARY KEY REFERENCES social_directory(member_id) ON DELETE CASCADE,
 workout_id varchar(120) NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE social_cheers (
 recipient_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
 actor_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
 workout_id varchar(120) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(recipient_id,actor_id,workout_id), CHECK(recipient_id<>actor_id)
);
ALTER TABLE social_notifications ADD COLUMN workout_id varchar(120);
-- 이전 자동 이름에 의존하지 않고 알림 유형/대상 제약만 교체합니다.
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='social_notifications'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%type%'
 LOOP EXECUTE format('ALTER TABLE social_notifications DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE social_notifications ADD CONSTRAINT notification_target CHECK (
 (type IN ('FRIEND_REQUEST','FRIEND_ACCEPTED') AND request_id IS NOT NULL AND post_id IS NULL AND workout_id IS NULL) OR
 (type='POST_LIKED' AND request_id IS NULL AND post_id IS NOT NULL AND workout_id IS NULL) OR
 (type='WORKOUT_CHEER' AND request_id IS NULL AND post_id IS NULL AND workout_id IS NOT NULL)
);
CREATE UNIQUE INDEX cheer_notification ON social_notifications(recipient_id,actor_id,workout_id) WHERE type='WORKOUT_CHEER';
