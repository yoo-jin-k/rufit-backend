-- state/nonce/handoff는 해시, 공급자 verifier만 짧은 시도 동안 암호문으로 보관한다.
CREATE TABLE auth_login_attempts (
 id uuid PRIMARY KEY,
 provider varchar(10) NOT NULL,
 client varchar(10) NOT NULL,
 return_uri varchar(512) NOT NULL,
 ip_hash char(64) NOT NULL,
 challenge char(43) NOT NULL,
 state_hash char(64) NOT NULL UNIQUE,
 nonce_hash char(64) NOT NULL,
 verifier_cipher text,
 status varchar(12) NOT NULL CHECK(status IN ('PENDING','CLAIMED','READY','CONSUMED','FAILED')),
 expires_at timestamptz NOT NULL,
 handoff_hash char(64),
 handoff_expires_at timestamptz,
 identity_issuer varchar(255),
 identity_subject varchar(255)
);
CREATE INDEX auth_attempt_ip ON auth_login_attempts(ip_hash,expires_at);
CREATE TABLE auth_rate_events (
 id bigserial PRIMARY KEY,
 bucket_hash char(64) NOT NULL,
 occurred_at timestamptz NOT NULL
);
CREATE INDEX auth_rate_bucket_time ON auth_rate_events(bucket_hash,occurred_at);
CREATE INDEX auth_rate_cleanup ON auth_rate_events(occurred_at);
