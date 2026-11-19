-- 회원 탈퇴 뒤에도 부정 이용 방지를 위해 신고 기록을 6개월 동안 남깁니다.
-- 탈퇴로 회원 행이 지워져도 신고가 함께 지워지지 않도록 외래 키를 풀고, 탈퇴 시각을 기록합니다.
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='social_reports'::regclass AND contype='f'
 LOOP EXECUTE format('ALTER TABLE social_reports DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE social_reports ADD COLUMN member_deleted_at timestamptz;
CREATE INDEX social_reports_deleted ON social_reports(member_deleted_at) WHERE member_deleted_at IS NOT NULL;
