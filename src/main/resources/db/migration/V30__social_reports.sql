-- 신고자와 대상은 공개 회원 식별자로 연결하며 신고 내역은 공개 API로 노출하지 않습니다.
CREATE TABLE social_reports (
 id uuid PRIMARY KEY,
 reporter_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
 target_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
 reason varchar(32) NOT NULL CHECK (reason IN ('SPAM','HARASSMENT','INAPPROPRIATE','OTHER')),
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK (reporter_id <> target_id)
);
CREATE INDEX social_reports_reporter_created ON social_reports(reporter_id,created_at);
