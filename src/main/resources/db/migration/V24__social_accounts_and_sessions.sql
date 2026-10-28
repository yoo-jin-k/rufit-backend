-- 기존 운동 owner 키는 바꾸지 않고 로그인 원장과 세션을 별도로 연결한다.
CREATE TABLE auth_accounts (
 id uuid PRIMARY KEY,
 owner_key varchar(100) NOT NULL UNIQUE,
 created_at timestamptz NOT NULL
);
CREATE TABLE auth_identities (
 provider varchar(10) NOT NULL CHECK (provider IN ('GOOGLE','KAKAO')),
 issuer varchar(255) NOT NULL,
 subject varchar(255) NOT NULL,
 account_id uuid NOT NULL REFERENCES auth_accounts(id),
 PRIMARY KEY(provider,issuer,subject)
);
CREATE TABLE auth_sessions (
 id uuid PRIMARY KEY,
 account_id uuid NOT NULL REFERENCES auth_accounts(id),
 created_at timestamptz NOT NULL,
 idle_expires_at timestamptz NOT NULL,
 absolute_expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 CHECK (idle_expires_at <= absolute_expires_at),
 CHECK (created_at < absolute_expires_at)
);
CREATE INDEX auth_sessions_account ON auth_sessions(account_id);
-- 사용한 토큰의 해시도 남겨 동일 grant의 재사용을 탐지한다. 원문은 저장하지 않는다.
CREATE TABLE auth_refresh_tokens (
 token_hash char(64) PRIMARY KEY,
 session_id uuid NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
 used_at timestamptz,
 created_at timestamptz NOT NULL
);
CREATE INDEX auth_refresh_session ON auth_refresh_tokens(session_id);
