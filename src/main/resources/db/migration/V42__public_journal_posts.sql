-- 오운완에 '전체 공개'를 추가합니다. 기존 글은 지금 범위를 그대로 둡니다.
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='journal_posts'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%visibility%'
 LOOP EXECUTE format('ALTER TABLE journal_posts DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE journal_posts ADD CONSTRAINT journal_posts_visibility CHECK (visibility IN ('PRIVATE','FRIENDS','PUBLIC'));
-- 소셜 피드는 공개 글을 최신순으로 읽습니다.
CREATE INDEX journal_posts_public_feed ON journal_posts(created_at DESC,id) WHERE visibility='PUBLIC';
