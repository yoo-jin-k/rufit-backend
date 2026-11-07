-- 이메일 자격은 회원 원장과 분리하여 기존 소셜 회원의 기록 소유권을 보존합니다.
create table auth_email_credentials (
 account_id uuid primary key references auth_accounts(id),
 email varchar(254) not null unique,
 password_hash varchar(512) not null,
 updated_at timestamptz not null
);
-- 인증 원문은 저장하지 않으며 연결 요청은 시작한 세션에 고정합니다.
create table auth_email_requests (
 id uuid primary key,
 email varchar(254) not null,
 purpose varchar(12) not null check (purpose in ('REGISTER','RESET','LINK')),
 token_hash char(64) not null,
 session_id uuid,
 expires_at timestamptz not null,
 consumed boolean not null default false
);
alter table auth_login_attempts add column link_session_id uuid;
create index auth_email_requests_expiry on auth_email_requests(expires_at);
