# 소셜 로그인 계약 v1 — 구현 기준

2026-10-29 PM 채택. 모바일 시스템 브라우저 + 서버 code 중계 + Rufit 자체 세션이다. A01~A07/D01~D06과 D07의 실패 표시를 채택한다. **이 문서 커밋 시점에는 신규 API 미구현**이며 구현/시험 결과는 후속 PR에 기록한다. 제공자 앱은 미등록이므로 실제 Kakao/Google 로그인 완료와 모의 제공자 시험을 구분한다.

## v0.3에서 달라진 결정

- 자체 JWT에 `sid`(세션 UUID)를 넣는다. 매 업무 인증에서 서명/issuer/audience/기간과 DB의 같은 회원·활성 grant·미사용/절대기한을 검사한다. logout/refresh 재사용 폐기가 커밋된 뒤 시작되는 인증은 기존 access도401로 거절한다. 이미 인증된 진행 중 요청을 소급 취소한다는 보장은 아니다.
- 세션 조회 DB 장애는503 `STORAGE_UNAVAILABLE`로 닫힌 상태를 유지한다. 조회 결과를 캐시해 폐기를 늦추지 않는다. 기존 `oidc` 프로필 동작은 보존하며 새 `social` 프로필과 동시 활성화하지 않는다.
- 숫자/실패 정책은 아래로 확정한다. Web 인증은 별도 예약이며 제품 전체 Web 지원 취소가 아니다. 앱 재시작 자동 복원은 보안 저장 logout 의사 검증 전 제한할 수 있다.

## HTTP·인증 경계

모든 JSON 인증 요청은 별도 transport, Bearer 없음, credentials=omit/redirect=error다. 업무 API는 Rufit Bearer 필수다. 제공자 access/ID/refresh는 업무 Bearer로 받지 않는다. callback/복귀는 시스템 인증 브라우저에서만 처리하며 AuthSession의 일반 code/state 파서와 앱 handoff 파서를 분리한다.

| API | 요청 | 성공 |
| --- | --- | --- |
| POST /api/v1/auth/login-attempts | provider KAKAO/GOOGLE, client IOS/ANDROID, returnRoute APP_LOGIN, codeChallenge, codeChallengeMethod S256 | 201 attemptId, authorizationUrl, expiresAt |
| GET /api/v1/auth/callback/{provider} | 경로는 `google` 또는 `kakao`. 제공자의 code+state 또는 error+state | 302 등록된 앱 URL에 attemptId+handoffCode, 취소는 attemptId+error=LOGIN_CANCELLED |
| POST /api/v1/auth/session-exchanges | attemptId UUID, handoffCode, codeVerifier | 200 아래 세션 JSON |
| POST /api/v1/auth/refresh | refreshToken | 200 같은 account.id/sessionId, 새 토큰 쌍 |
| POST /api/v1/auth/logout | refreshToken | 204 현재 grant 폐기. 정상형식의 알려지지 않은/이미 폐기된 값도204 |
| GET /api/v1/me | Rufit Bearer | 200 {"id":"불투명한기존ownerKey"}, 기존 응답 유지 |

시작 요청 예:

```json
{"provider":"GOOGLE","client":"IOS","returnRoute":"APP_LOGIN","codeChallenge":"E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM","codeChallengeMethod":"S256"}
```

시작 응답 예(값은 설명용):

```json
{"attemptId":"11111111-1111-4111-8111-111111111111","authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth?state=example","expiresAt":"2026-10-29T01:05:00Z"}
```

교환 요청 예(공개 PKCE 시험 벡터, 실제 자격 아님):

```json
{"attemptId":"11111111-1111-4111-8111-111111111111","handoffCode":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","codeVerifier":"dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"}
```

세션 응답 예:

```json
{
  "account":{"id":"example-opaque-owner","isNewAccount":true,"profileStatus":"MISSING"},
  "sessionId":"22222222-2222-4222-8222-222222222222",
  "tokenType":"Bearer",
  "accessToken":"example-signed-rufit-jwt",
  "accessTokenExpiresAt":"2026-10-29T01:10:00Z",
  "refreshToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "refreshTokenExpiresAt":"2026-11-12T01:00:00Z"
}
```

모든 응답 필드는 필수/non-null. 시간은 UTC ISO8601. account.id는1~100 UTF-16 code unit이며 공백만 있는 값은 불가, 원문 trim/자르기/접두어 해석 금지. 현재 OIDC owner 생성식(69 ASCII)을 보존한다. 검증된 동일 issuer+sub는 같은 owner, 이메일로 자동 병합하지 않는다. Google의 허용 issuer 별칭은 제공자 검증 후 HTTPS canonical issuer로 매핑하며 기존 OIDC가 허용했던 HTTPS 식별자의 키를 유지한다. 다른 기존 브로커 계정은 증명/명시적 정책 없이 합치지 않는다. profileStatus=MISSING/PRESENT는 프로필 존재이며 profile404로 신규 계정을 판정하지 않는다. refresh의 isNewAccount는false다.

## 기간·크기·요청 제한

- access10분, JWT 시계 오차30초. refresh 미사용14일/세션 생성부터 절대30일. 성공한 회전만 미사용기한을 연장한다. refresh 만료=min(회전+14일,생성+30일), access 만료=min(발급+10분,refresh 만료). DB 기한은 now>=expiresAt 거절하며 시계 오차를 더하지 않는다.
- attempt5분, handoff60초와 attempt 만료 중 빠른 시각. FE 활성 attempt1개/앱 인스턴스. 잘못된 verifier가 코드를 소비하지 않는다. 제공자 code 교환은 시도당1회, handoff 소비+회원/세션 저장은 한 트랜잭션이다.
- access 최대4096 ASCII 문자. refresh/handoff는 CSPRNG32바이트→padding 없는 base64url43자. verifier43~128의 `[A-Za-z0-9._~-]`, challenge는 canonical S256 base64url43자. attemptId/sessionId UUID36자.
- 인증 POST body 최대2048 UTF-8바이트(초과413 PAYLOAD_TOO_LARGE), 세션 JSON 최대16384바이트. provider callback code 최대2048자/state43자, 중복 query 및 code/error 동시 입력은400. 제공자 응답은 별도64KiB 제한, ID token16KiB 제한. 외부 오류 본문은 사용자/로그에 그대로 내보내지 않는다.
- 시작20회/60초+100회/1시간/IP, 미완료 attempt30개/IP. 나머지 인증 POST120회/60초/IP, handoff 오증명5회/60초/(attempt,IP). 확인된 세션 refresh12회/60초/sessionId. 이동 구간이며 DB 공유 카운터로 여러 인스턴스에도 적용, 초과429+Retry-After 정수초. 틀린 verifier 제한은 시도 영구 폐기가 아니다.
- 처음에는 직접 연결 remoteAddr만 사용하며 임의 X-Forwarded-For를 신뢰하지 않는다. 프록시 뒤에서는 같은 IP로 합쳐질 수 있으므로 운영 전 신뢰 프록시/접속 IP 정책을 검증한다. logout은 refresh 전용 제한을 공유하지 않는다.

## 원자성·실패·보존

1. 시작 시 FE handoff challenge와 provider/client/returnRoute를 불변으로 저장한다. BE는 독립된 provider PKCE/state/nonce를 만든다. state/nonce/handoff/refresh 원문은 DB에 저장하지 않고 해시로 비교한다. callback까지 필요한 provider verifier만 서버 설정 키로 AES-GCM 암호화한다. 외부 원본 code·ID/access/refresh 토큰은 저장하지 않는다.
2. callback의 state를 검증하고 원자적으로 CLAIMED로 바꾼 뒤 외부 교환한다. 외부 호출 중 DB 트랜잭션/행 잠금을 유지하지 않는다. 동시 callback은 외부 요청을 추가하지 않는다. 유실/프로세스 중단의 CLAIMED를 재사용하지 않고 만료 후 새 로그인한다.
3. 검증한 ID token의 issuer/aud/azp/exp/iat/nonce/sub와 RS256/kid를 검사한다. 사용자 입력 issuer/JWKS/redirect URL을 받지 않는다. 외부 token/JWKS URL은 제공자별 고정 허용 목록, HTTPS, redirect 추적 금지, 응답 크기/시간 제한. 테스트 프로필에서만 loopback fake 제공자를 허용한다.
4. handoff를 한 번 소비하고 회원/identity/grant/refresh 해시를 함께 저장한다. 동시에 교환하면 하나만200, 같은 올바른 비밀의 재사용409. 원장 생성 여부로 isNewAccount를 결정한다. 기존 운동 소유권/ID 행을 수정하지 않는다.
5. 1 sessionId=1 grant=1 회전 체인. 세션 행을 잠그고 refresh를 한 번 회전한다. 과거 유효 refresh 재사용은 같은 grant를 폐기한 뒤401 SESSION_EXPIRED. 동시 두 요청은 최대1회 회전하지만 두 번째 재사용으로 새 토큰도 폐기된다. 다른 세션은 유지. 과거 해시는 grant 절대기한+30초까지 보관 후 정리 가능하다.
6. handoff/refresh의 처리 여부 불명확은 자동 재전송0회, 응답 복구 보관/유예0초, 재로그인. timeout/Abort/JSON손상은 미전송 증거가 아니다. 원문 운동/outbox/기존 계정 공간을 보존한다. 업무 변경을 최신 version으로 자동 재적용하지 않는다.
7. FE는 새 access로 /me를 재검증하고 같은 서버/account.id(갱신 시sessionId도 동일)의 새 facade를 만든다. 보안 저장 성공/동일 authEpoch 후 공개한다. 로그인/갱신/복귀/저장 읽기·쓰기·삭제를 직렬 조정하며 오래된 결과/삭제가 새 세션을 덮어쓰지 못하게 한다. 토큰은 운동 저장소에 넣지 않는다.
8. logout은 현재 grant만 폐기. FE는 즉시 epoch 무효화, 서버 응답 대기5초 후 미확인 표시. 서버 재시도용 비밀 영구 저장/자동 재시도0회. 로컬 삭제 실패는 완료로 표시하지 않고 API 차단/정리 재시도. 앱 재시작 자동 복원은 tombstone/삭제 실패 시험 전 제한 가능. 운동 삭제·제공자 동의 철회·전체 기기 logout은 하지 않는다.

인증/callback 응답은 Cache-Control:no-store, Referrer-Policy:no-referrer. query·Authorization·body·Location·예외의 제공자 원문을 로그/분석에 기록하지 않는다. callback에 제3자 리소스 없음. 외부 프록시 로그 설정은 운영 연결 전 별도 확인한다.

| 오류 | HTTP/code | FE 처리 |
| --- | --- | --- |
| 형식/필수/지원하지 않는client·returnRoute | 400 VALIDATION_FAILED/INVALID_INPUT/INVALID_JSON | 수정, WEB은 아직 활성화 불가 |
| 비밀/증명 오류 | 401 AUTHENTICATION_REQUIRED | 새 로그인, 원문 보존 |
| 세션 만료·폐기·refresh 재사용 | 401 SESSION_EXPIRED | 재로그인, 자동 업무 재적용 금지 |
| 유효 시도 중복/소비 | 409 LOGIN_ATTEMPT_CONFLICT | 받은 현재 세션 유지, 없으면 새 로그인 |
| 크기 초과 | 413 PAYLOAD_TOO_LARGE | 자동 재전송 금지 |
| 빈도/활성 시도 제한 | 429 RATE_LIMITED + Retry-After | 대기 후 사용자 조작 |
| 제공자 미등록·비활성 | 503 AUTH_PROVIDER_UNAVAILABLE | 실제 로그인 가능으로 표시하지 않음 |
| 제공자/키 일시 장애 | 503 AUTHENTICATION_UNAVAILABLE | 실패와 구분, 원문 보존 |
| DB 장애(인증 포함) | 503 STORAGE_UNAVAILABLE | 인증/저장 성공으로 처리하지 않음 |

오류는 기존 application/problem+json `{type,title,status,detail,instance,code}`와 X-Request-Id를 유지한다. 원문 비밀을 errors/detail에 포함하지 않는다.

## 설정 이름·테스트 자원

| 환경 변수 | 용도 |
| --- | --- |
| SPRING_PROFILES_ACTIVE=social | 신규 세션 인증; oidc/local과 혼용하지 않음 |
| RUFIT_SESSION_ISSUER / RUFIT_SESSION_AUDIENCE / RUFIT_SESSION_KEY_ID | Rufit JWT 발급/검증 경계, issuer는 HTTPS |
| RUFIT_SESSION_PRIVATE_KEY_PATH | 환경에서 주입한 RSA PKCS8 PEM 파일 경로. 실제 키 커밋/로그 금지 |
| RUFIT_SOCIAL_TRANSIENT_KEY | provider verifier 암호화용32바이트 키의 base64; 실제 키는 설정에서 주입 |
| RUFIT_GOOGLE_CLIENT_ID / RUFIT_GOOGLE_CLIENT_SECRET / RUFIT_GOOGLE_CALLBACK_URI | Google Web Server OAuth 등록. callback 끝은 /api/v1/auth/callback/google |
| RUFIT_KAKAO_CLIENT_ID / RUFIT_KAKAO_CLIENT_SECRET / RUFIT_KAKAO_CALLBACK_URI | Kakao REST 키/secret/OIDC 등록. callback 끝은 /api/v1/auth/callback/kakao |
| RUFIT_IOS_LOGIN_RETURN_URI / RUFIT_ANDROID_LOGIN_RETURN_URI | APP_LOGIN의 정확한 앱 복귀 주소, query/fragment/userinfo 금지. 앱 등록과 일치해야 함 |
| DB_URL / DB_USERNAME / DB_PASSWORD | 기존 PostgreSQL 설정 |

제공자 등록값이 없으면 해당 제공자의 시작 API는503이다. 자체 서명 설정이 없거나 잘못되면 social 시작을 거절한다. 테스트에서는 매 실행 메모리/임시 파일에 만든 시험 키와 가짜 제공자만 사용한다. 환경변수 값을 출력하거나 실제 콘솔을 생성하지 않는다.

독립 작업 공간 `rufit-backend-auth`, 계약 브랜치 `codex/social-login-v1-contract`. 기존 PR62를 의존성으로 유지하고 developer/main은 수정하지 않는다. 테스트용 컨테이너 `rufit-auth-test-db`, 호스트127.0.0.1:5437, DB/role은 rufit_test. JVM 테스트는 같은 컨테이너 네트워크의 localhost:5432, HTTP는 임의 loopback 포트로 구동해 FE의5435/서버와 분리한다. 후속 순서는 계정·grant/자체 JWT → code·handoff → refresh/logout/전체HTTP회귀의 작은 PR이다.

## 확인한 공식 자료

2026-10-29 [Google Web Server OAuth](https://developers.google.com/identity/protocols/oauth2/web-server), [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect), [Kakao REST/OIDC](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [RFC9700 refresh 회전](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2)를 확인했다. 양쪽 OIDC 문서의 S256 지원과 issuer/JWKS/nonce 요구를 반영한다. 실제 등록값/앱 복귀의 성공은 별도 실기기 시험 전까지 미검증이다.
