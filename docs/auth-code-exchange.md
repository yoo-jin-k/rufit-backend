# 로그인 코드 교환 구현

2026-10-29. PR63의 v1 계약과 PR64의 세션 기반에 이어 구현했다. 실제 제공자 앱은 미등록이며 아래 결과는 가짜 제공자/JWKS와 격리 PostgreSQL의 결과다.

## 구현한 API

| API | 성공 | 주요 검증 |
| --- | --- | --- |
| POST /api/v1/auth/login-attempts | 201, attemptId/authorizationUrl/expiresAt | 모바일 client/고정 복귀 경로, S256, 5분, IP 제한 |
| GET /api/v1/auth/callback/{provider} | 302, 등록 앱의 attemptId/handoffCode | state/nonce/provider, 중복 query, 독립 provider PKCE, RS256 ID token |
| POST /api/v1/auth/session-exchanges | 200, SessionView | FE verifier, 60초 handoff, 한 번만 소비, 회원/세션 원자 저장 |

- V25는 암호화된 provider verifier와 비밀 해시를 갖는 시도 원장 및 DB 공유 요청 카운터를 추가한다. 기존 마이그레이션/기록은 변경하지 않는다.
- callback은 CLAIMED를 먼저 커밋하고 외부 호출한다. 같은 callback이 동시에 들어와도 외부 code 교환은 한 번이다. 앱으로 외부 code/state/토큰을 반환하지 않는다.
- 외부 token/JWKS 주소는 Google/Kakao별로 고정한다. redirect를 추적하지 않고 연결/읽기 제한 및 전체 응답 읽기 기한, 64KiB 응답 한도를 적용한다. 임의 URL/issuer/email로 신원을 만들지 않는다.
- 일회용 handoff와 회원·세션 저장은 한 트랜잭션이다. DB 오류는 인증 컨트롤러에서 안전한 503으로 처리해 SQL 원문이나 재전송된 /error의 401이 노출되지 않게 한다.
- OpenAPI에는 익명 인증 요청, 201/302/200, 413/429와 Retry-After를 반영한다. 최종 구조는 `target/auth-openapi.json` 시험 산출물로 확인한다(생성물 미커밋).

## 검증

Java21/Maven, 별도 `rufit-auth-test-db`(host5437), DB/role `rufit_test`. HTTP 시험은 실제 임의 loopback 포트의 Spring 서버와 가짜 OAuth 서버 사이에서 수행했다.

- `LoginHttpTests` 10개: Google/Kakao 시작→승인→callback→exchange→me, callback/교환 경쟁, 오증명/빈도, 취소/만료, 잘못된 issuer/aud/azp/nonce/서명/기간, 외부 redirect/장애/응답 초과, DB 원자 롤백, 생성 OpenAPI.
- `ProviderConfigurationTests` 3개: test-only loopback 제한, 실제 제공자 목적지 고정, 서버의 복귀 경로 제한.
- 신규 세션 기반 7개와 기존 OIDC/API/CORS 13개 포함 총33개 통과. 명세/CORS 마지막 변경 후 관련12개도 재검증 통과했다.
- 실제 Kakao/Google 로그인, 앱 복귀 등록/실기기, 운영 프록시/운영 DB는 미검증이다. provider 등록값이 없으면 시작 API는503이다.

다음 단위: refresh 회전·재사용 즉시 폐기·logout과 원장 정리, 전체 회귀, FE 전용 fake HTTP 기동 안내. 현재 단계에는 refresh/logout 구현 완료를 주장하지 않는다.
