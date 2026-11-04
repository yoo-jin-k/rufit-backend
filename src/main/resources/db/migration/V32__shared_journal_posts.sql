CREATE TABLE journal_posts (
 id uuid PRIMARY KEY, member_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
 source_id varchar(120) NOT NULL, memo varchar(200) NOT NULL, workout_date date NOT NULL,
 summary varchar(160) NOT NULL, visibility varchar(8) NOT NULL CHECK(visibility IN ('PRIVATE','FRIENDS')),
 version bigint NOT NULL, content_hash varchar(64) NOT NULL,
 thumbnail bytea, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(member_id,source_id)
);
CREATE INDEX journal_posts_member ON journal_posts(member_id,created_at DESC,id);
CREATE TABLE journal_photos (
 post_id uuid NOT NULL REFERENCES journal_posts(id) ON DELETE CASCADE,
 position integer NOT NULL CHECK(position BETWEEN 0 AND 4), content bytea NOT NULL,
 PRIMARY KEY(post_id,position)
);
CREATE TABLE journal_likes (
 post_id uuid NOT NULL REFERENCES journal_posts(id) ON DELETE CASCADE,
 member_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
 PRIMARY KEY(post_id,member_id)
);
ALTER TABLE social_notifications ALTER COLUMN request_id DROP NOT NULL;
ALTER TABLE social_notifications ADD COLUMN post_id uuid REFERENCES journal_posts(id) ON DELETE CASCADE;
ALTER TABLE social_notifications DROP CONSTRAINT social_notifications_type_check;
ALTER TABLE social_notifications ADD CHECK(type IN ('FRIEND_REQUEST','FRIEND_ACCEPTED','POST_LIKED'));
ALTER TABLE social_notifications ADD CHECK((type='POST_LIKED' AND post_id IS NOT NULL AND request_id IS NULL) OR (type<>'POST_LIKED' AND request_id IS NOT NULL AND post_id IS NULL));
CREATE UNIQUE INDEX journal_like_notification ON social_notifications(recipient_id,actor_id,post_id) WHERE type='POST_LIKED';
