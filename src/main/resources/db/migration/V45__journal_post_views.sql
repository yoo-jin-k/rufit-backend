-- 오운완 조회수: 게시물 상세를 연 회원을 한 번씩만 셉니다(작성자 본인은 세지 않음).
CREATE TABLE journal_views (
 post_id uuid NOT NULL REFERENCES journal_posts(id) ON DELETE CASCADE,
 member_id uuid NOT NULL REFERENCES social_directory(member_id) ON DELETE CASCADE,
 viewed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(post_id,member_id)
);
