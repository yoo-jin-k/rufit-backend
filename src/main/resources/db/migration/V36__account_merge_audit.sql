-- 공개 API로 노출하지 않는 통합 이력입니다. 덮어쓰기 전에 프로필·설정을 보존합니다.
CREATE TABLE auth_account_merges (
 source_account uuid PRIMARY KEY REFERENCES auth_accounts(id),
 target_account uuid NOT NULL REFERENCES auth_accounts(id),
 snapshot jsonb NOT NULL,
 merged_at timestamptz NOT NULL DEFAULT now(),
 CHECK(source_account<>target_account)
);
