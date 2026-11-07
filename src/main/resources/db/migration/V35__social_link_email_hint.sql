-- 연결 인증에서 확인한 이메일을 일시적으로 암호화하여 보관합니다. 만료 시 기존 시도 정리가 삭제합니다.
ALTER TABLE auth_login_attempts ADD COLUMN identity_email_cipher text;
