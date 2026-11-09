# 자체 세션 인증 기반

2026-10-29. 확정 계약 social-login-v1.md 다음의 첫 구현 단위다. 새로운 로그인 시작/교환/refresh/logout endpoint는 후속 단위이며 이 단계만으로 실제 로그인이 완료되지 않는다.

- V24: auth_accounts, auth_identities, auth_sessions, auth_refresh_tokens. 회원과 소셜 식별자를 분리하고 기존 issuer+sub의69자 owner를 그대로 사용한다. 이메일은 식별/병합에 사용하지 않는다.
- social 프로필: 설정 주입 RSA 키로 RS256 자체 JWT를 만들고 sid/회원/기간/서명을 검증한다. 업무 요청마다 DB의 활성 grant와 기한을 확인하며 장애 시503 STORAGE_UNAVAILABLE. 요청에서 owner를 받지 않는다.
- 기존 oidc/local 동작은 보존한다. social과 oidc/local 동시 활성화는 거절하고 social은 쿠키/Basic/개발 CSRF route를 사용하지 않는다.
- 세션/refresh 원문은 toString에 노출하지 않고 DB에는 refresh 해시만 저장한다. 회원 첫 생성과 grant 발급은 트랜잭션으로 묶는다.
- 개별 SQL 잠금과 외부 code 호출의 경계를 명확히 하기 위해 인증 원장은 JdbcTemplate 트랜잭션으로 구현했다. 기존 운동/루틴/프로필의 JPA 구조는 유지한다.

검증: Java21 패키징, 전용 PostgreSQL17의 SessionFoundationTests7 + 기존 OIDC/API 계약/접근 정책13 =20개 실패0. 동시 첫 로그인, 기존 owner와 자료 접근, 다른 owner 격리, 이미 서명한 토큰의 DB 폐기/만료, 위조/없는sid/다른회원/Basic 거절, 실제 DB 조회 실패→503→복구, social OpenAPI와 CSRF 비노출을 확인했다. 제공자 등록/운영 배포/실제 소셜 계정 검증은 하지 않았다.
