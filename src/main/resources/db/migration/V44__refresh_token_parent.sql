-- 갱신 토큰이 어느 토큰을 써서 발급됐는지 기록합니다. 응답을 받지 못한 재시도를 한 번만 허용할 때 씁니다.
ALTER TABLE auth_refresh_tokens ADD COLUMN parent_hash char(64);
