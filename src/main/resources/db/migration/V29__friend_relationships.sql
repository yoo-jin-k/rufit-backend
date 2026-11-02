CREATE TABLE friend_requests (
  id uuid PRIMARY KEY,
  requester_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
  target_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
  state varchar(10) NOT NULL CHECK (state IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (requester_id <> target_id)
);
CREATE UNIQUE INDEX one_pending_friend_pair ON friend_requests(least(requester_id,target_id),greatest(requester_id,target_id)) WHERE state='PENDING';
CREATE TABLE friendships (
  first_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
  second_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
  PRIMARY KEY(first_id,second_id), CHECK(first_id < second_id)
);
CREATE TABLE social_blocks (
  blocker_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
  blocked_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
  PRIMARY KEY(blocker_id,blocked_id), CHECK(blocker_id <> blocked_id)
);
CREATE INDEX incoming_friend_requests ON friend_requests(target_id,created_at) WHERE state='PENDING';
CREATE INDEX outgoing_friend_requests ON friend_requests(requester_id,created_at) WHERE state='PENDING';
