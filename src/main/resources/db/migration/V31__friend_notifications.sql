-- 기존 대기 신청도 새 서버 알림함에서 확인할 수 있도록 보충합니다.
CREATE TABLE social_notifications (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  recipient_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
  actor_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
  request_id uuid NOT NULL REFERENCES friend_requests(id) ON DELETE CASCADE,
  type varchar(24) NOT NULL CHECK(type IN ('FRIEND_REQUEST','FRIEND_ACCEPTED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  read_at timestamptz,
  UNIQUE(recipient_id,request_id,type), CHECK(recipient_id<>actor_id)
);
CREATE INDEX social_notifications_inbox ON social_notifications(recipient_id,id DESC);
INSERT INTO social_notifications(recipient_id,actor_id,request_id,type,created_at)
SELECT target_id,requester_id,id,'FRIEND_REQUEST',created_at FROM friend_requests WHERE state='PENDING';
