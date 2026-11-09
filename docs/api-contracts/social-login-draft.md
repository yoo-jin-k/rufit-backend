# BE-02 소셜 로그인 API 초안 v0.3

> 2026-10-29: 구현 기준은 [확정 계약 v1](social-login-v1.md)로 이동했다. 아래는 결정 전 검토 이력이다.

상태: **PM 검토용 미확정 초안, 구현 전**. KAKAO·GOOGLE 제공자 요구는 확정됐지만 세션 발급 주체·로그인 진입 방식·계정 연결/이전 정책은 미확정이다. 이 문서는 [공백 점검](../authentication-gap-analysis.md)의 **RuFit 자체 세션 + 서버 authorization-code 중계 후보안**을 구체화한다. PM 채택 전 실제 FE 서버 호출·런타임 컨트롤러·DB 테이블·생성 OpenAPI에 반영하지 않는다. FE는 아래 상태/예시로 목업만 준비할 수 있다.

v0.2(2026-09-14): [적대적 시나리오 검토](../authentication-adversarial-review.md)로 앱 시작 PKCE/handoff 불변 결합·동시 소비·유출, WEB 별도 계약, refresh 재사용 철회, logout 잔존 보장을 보완했다. v0.1의 JSON 모양은 유지하며, WEB은 활성화 가능한 확정 플랫폼이 아닌 예약값임을 명확히 했다. 실제 API 버전 변경은 아니다.

v0.3(2026-09-14): FE 검토 A01~A07에 맞춰 계정 ID 상한·별도 인증 transport·토큰 교체/보안 저장 순서를 보완했다. [FE 대응표·PM 수치 결정표](social-login-fe-alignment.md)가 추가 계약 근거다. JSON 모양은 유지하고 수치/실패 정책은 추천안으로 분리한다. 런타임 적용은 아직 없다.

## 현재 사용할 수 있는 계약

| API | 요청/응답 | 상태 |
| --- | --- | --- |
| GET /api/v1/me | 검증된 RuFit API용 Bearer → 200 {"id":"서버의불투명계정키"} | 구현됨. 클라이언트가 제공자 sub/email로 id를 만들지 않음 |
| GET /api/v1/profile | 같은 Bearer → 200 프로필 또는404 | 구현됨. 404는 프로필이 없다는 뜻이며 신규 계정 판정 아님 |
| POST/PUT /api/v1/profile | [기존 요청·응답](../profile-api.md) | 구현됨. 비공개 프로필과 현재 버전 규칙 유지 |

이하 /api/v1/auth/*는 전부 신규 제안 경로다. 현재 보안 설정의 공개 health 경로와 혼동하지 않는다.

## 제안 흐름과 인증 경계

1. FE는 로그인 시도마다 앱 복귀용 verifier를 새로 만들고 S256 challenge만 서버에 보낸다. 이 verifier는 아래 handoff 교환용이며 제공자 요청의 PKCE 값과 섞지 않는다.
2. BE는 provider/client/등록된 앱 복귀 경로에 묶인 시도, state·nonce·제공자용 PKCE를 준비한다. FE가 임의 issuer/JWKS/redirect URL을 지정하지 않는다.
3. 시스템 브라우저가 제공자 로그인을 마치면 등록된 HTTPS BE callback이 code/state를 받는다. BE가 state·code 교환·ID 증명을 검증하고 한 번 사용할 handoffCode를 만든다.
4. BE는 등록된 앱 복귀 주소로 attemptId와 handoffCode만 전달한다. 액세스/refresh/ID 토큰이나 제공자 원본 code를 앱 복귀 URL에 넣지 않는다.
5. FE가 처음의 verifier로 handoffCode를 교환하면 RuFit 세션을 받는다. 이후 /me로 계정 식별자를 확인하고 해당 서버 주소+계정의 저장 공간을 연다.

각 단계의 nonce/state/PKCE는 동일 시도에 묶고 다른 시도/제공자/클라이언트의 값과 바꿔 쓸 수 없다. 공식 제공자 흐름과 선택한 앱 복귀 방식의 실제 호환성 검증은 구현의 선행 조건이다. 발급자·키·audience 검증 기반은 기존 구현을 재사용하되, nonce/시도 바인딩과 서비스 발급은 추가 작업이다.

attempt에 시작 challenge/provider/client/returnRoute를 변경 불가로 저장하고, handoffCode는 그 attempt에만 연결한다. 교환에서는 최초 verifier를 검사하며 복귀 URL에서 새로운 verifier를 받아 대체하지 않는다. 잘못된 verifier는 코드를 소비하지 않는다. PKCE는 verifier 보유 앱 인스턴스의 결합이며 기기/정품 앱의 신원을 증명하는 기능과 다르다. 앱 링크 소유권·등록은 별도 외부 검증 대상이다.

FE는 login-attempts/session-exchanges/refresh/logout용 별도 인증 transport를 둔다. 이 네 JSON 경로에는 기존 Bearer를 붙이지 않고 credentials=omit/redirect=error를 유지한다. 업무 API의 Bearer 요구를 전역 해제하지 않는다. authorizationUrl은 시스템 인증 브라우저에서 열고, 앱의 attemptId/handoffCode는 별도 파서로 처리한다. 표준 제공자 code/state 파서를 그대로 연결하지 않는다.

## 제안 엔드포인트·요청·응답

아래 토큰·코드·URL·시간은 설명용이며 실제 접속값/발급값이 아니다. 모든 API 본문은 application/json, 인증/교환 응답은 Cache-Control: no-store를 제안한다. 시간은 ISO8601 UTC이며 예시 간격이 운영 토큰 수명 결정을 뜻하지 않는다.

### 로그인 시작

POST /api/v1/auth/login-attempts, 로그인 전 호출. 요청량 제한·시도 만료는 이후 구현해야 한다.

```json
{"provider":"KAKAO","client":"IOS","returnRoute":"APP_LOGIN","codeChallenge":"E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM","codeChallengeMethod":"S256"}
```

201:

```json
{"attemptId":"11111111-1111-4111-8111-111111111111","authorizationUrl":"https://provider.example.invalid/authorize?state=example","expiresAt":"2026-09-13T15:05:00Z"}
```

필수값: provider=KAKAO/GOOGLE, client=IOS/ANDROID/WEB, returnRoute는 서버에 등록한 키. 미설정 플랫폼·제공자는 활성화하지 않는다. codeChallenge는 S256의 43자 base64url 결과, method는 S256만 허용한다. URL 대신 경로 키를 받아 임의 redirect를 막는다. 시도 수명과 동시 시도 제한 숫자는 PM/보안 검토에서 확정한다.

WEB은 미래 계약의 예약값이다. Web BFF/쿠키·CSRF 또는 별도 SPA의 토큰 보관 경계가 확정되기 전에는 활성화하지 않는 제안이다. 모바일의 토큰 응답을 브라우저 localStorage에 저장하거나 현재 OIDC의 CSRF 해제 설정에 쿠키 인증을 덧붙이지 않는다. [Web 분리 검토](../authentication-adversarial-review.md)를 먼저 확정한다.

### 제공자 callback

GET /api/v1/auth/callback/{provider}, 제공자가 보내는 query code/state 또는 error/state. FE가 직접 업무 API처럼 호출하는 경로가 아니다.

- state가 알려진 시도와 맞아야 provider·code·nonce·등록된 redirect까지 검증한다. code와 error를 동시에 받으면 거절한다.
- 정상일 때 302로 등록된 앱 복귀 경로에 attemptId/handoffCode를 전달한다. 알려지지 않은 state는 redirect하지 않고400 고정 오류다.
- 사용자 취소는 검증된 시도의 등록된 복귀 경로로 LOGIN_CANCELLED와 attemptId만 전달한다. 취소 때문에 기존 로컬 운동을 삭제하지 않는다.
- 동일 callback이 중복돼도 계정/세션을 다시 만들지 않는다. code 교환 도중 응답을 잃으면 제공자 code를 무조건 재사용하지 않는다. 상태 불명확 시 그 시도를 종료하고 새 로그인으로 복구하는 후보안이다.
- 시도의 원자적 상태 전이로 외부 code 교환을 한 번만 시작한다. callback/복귀 응답은 no-store/no-referrer 및 제3자 리소스 배제를 제안하며 프록시·앱·분석 로그에 code/state/handoff/토큰·전체 Location을 남기지 않는다. 실제 로그 설정 검증은 별도다.

### 앱 handoff 교환

POST /api/v1/auth/session-exchanges, handoffCode와 verifier가 자격 증명이다. 기존 로그인 Bearer는 요구하지 않는 제안이다.

```json
{"attemptId":"11111111-1111-4111-8111-111111111111","handoffCode":"one-time-example-code","codeVerifier":"dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"}
```

200:

```json
{
  "account":{"id":"example-opaque-account-key","isNewAccount":false,"profileStatus":"MISSING"},
  "sessionId":"22222222-2222-4222-8222-222222222222",
  "tokenType":"Bearer",
  "accessToken":"example-rufit-access-token",
  "accessTokenExpiresAt":"2026-09-13T15:15:00Z",
  "refreshToken":"example-rufit-refresh-token",
  "refreshTokenExpiresAt":"2026-10-13T15:00:00Z"
}
```

attemptId UUID, handoffCode 비어 있지 않은 단기 비밀, verifier는43~128자의 PKCE 허용 문자. challenge 일치·시도/코드 만료·단일 사용을 검증한다. 잘못된 비밀은401, 인증된 동일 handoff의 재사용은409을 제안한다. 세션 생성과 코드 소비는 원자적으로 처리해야 한다.

account.id는 /me.id와 일치하는 불투명 문자열이며 **1~100 UTF-16 code unit, 공백만 있는 값 불가**를 제안한다. FE는 원문 그대로 비교·저장하며 자르거나 trim하지 않는다. UUID·고정 길이·issuer 접두어는 가정하지 않는다. 현재 OIDC 생성 키는69 ASCII 문자지만 /me 컨트롤러 자체의 길이 검증은 없다는 [코드/기존 자료 근거](social-login-fe-alignment.md)를 함께 본다. isNewAccount는 회원 원장의 첫 생성 여부로 판정해야 하며 현재 코드에는 이 원장이 없다. profileStatus=MISSING/PRESENT는 프로필 존재만 나타낸다. 이메일·제공자 ID로 FE가 합치거나 프로필404를 신규 계정으로 해석하지 않는다. 세션 필드와 토큰은 모두 필수·non-null인 제안이다.

### 갱신·현재 세션 로그아웃

| 제안 API | 요청 | 응답/의미 |
| --- | --- | --- |
| POST /api/v1/auth/refresh | {"refreshToken":"example-rufit-refresh-token"} | 200, 위와 같은 account/session/token 응답. 같은 계정·sessionId, 새 토큰 쌍 |
| POST /api/v1/auth/logout | {"refreshToken":"example-rufit-refresh-token"} | 204. 이 비밀이 가리키는 현재 refresh 세션만 폐기. 이미 폐기된 경우도204 |

이 토큰은 소셜 제공자의 refresh 토큰과 별개다. 클라이언트는 서비스 refresh를 제공자 토큰 endpoint로 보내지 않는다. 토큰 문자열은 필수이며 원문 로그 금지, 명세 최대 길이는 서명/토큰 형식 결정 후 확정한다. logout은 요청 body의 비밀로 해당 세션만 식별한다. 다른 계정/전체 기기를 지정하는 ownerId/session 목록 필드는 받지 않는 안이다.

refresh 성공의 isNewAccount는 false이며 기존 /me.id를 유지한다. 프로필 존재 상태는 갱신될 수 있다. 오래된 refresh 응답이 새 로그인 결과를 덮어쓰지 않도록 FE의 세션 세대도 확인한다.

교환/refresh 뒤 새 access를 캡처한 후보 session facade로 /me를 재검증한다. 응답 account.id와 정확히 같고 refresh라면 이전 서버·계정·sessionId도 같아야 한다. 보안 저장 성공과 authEpoch 재확인 후 새 facade를 공개한다. getter 교체만으로 기존 facade가 새 토큰을 사용한다고 가정하지 않는다.

refresh는 같은 서버·계정·sessionId·authEpoch에서 동시에 하나만 요청하고 이전 토큰을 새 토큰으로 교체하는 회전안을 제안한다. 한 번 사용된 토큰의 재사용은401 SESSION_EXPIRED로 재로그인을 요구하는 안이며, 탈취 탐지 시 세션 폐기 범위는 PM 검토 항목이다. 서비스 토큰 수명/보관·서명 키 운영도 미확정이다. 구체적인 수치와 체인 범위는 [D01~D07 추천안](social-login-fe-alignment.md)을 검토한다.

회전으로 재생을 방지하려면 유효했던 과거 토큰의 재사용을 찾았을 때 같은 grant의 활성 refresh도 폐기하는 기준을 검토해야 한다. 단순히 과거 토큰만401로 거절하면 먼저 탈취해 회전한 공격자가 계속 갱신할 수 있다. 따라서 토큰의 회전 관계와 grant/현재 세션 범위를 먼저 명세한다. 임의의 알 수 없는 토큰을 보냈다고 타인의 세션을 폐기해서는 안 된다. [RFC 9700 refresh 보호](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2).

| refresh 상황 | 이 초안의 단순 회전 후보안 | 복구 보관 대안에서 먼저 정할 것 |
| --- | --- | --- |
| 응답을 정상 수신 | /me 재검증 후 새 refresh/메타데이터를 직렬 보안 저장하고 access는 메모리에서 교체 | 동일 |
| 서버 처리 전에 명확히 전송 실패 | 자동 무한 반복 없이 동일 요청을 다시 시작할 수 있으나, 단순 timeout을 이 상황으로 단정하지 않음 | 같은 요청 식별자 유지 |
| 처리/회전 후 응답 유실 여부 불명확 | FE는 재로그인. 쓰던 토큰을 새 요청처럼 재전송해 성공으로 간주하지 않음 | 같은 요청 키+같은 입력만 원래 결과 복구, 보호된 결과 보관 기간/조회 자격 필요 |
| 이전 refresh가 다시 들어옴 | 재사용으로 거절. 정상 유실인지 탈취인지 요청만으로 단정 불가 | 허용된 동일 요청 복구와 다른 요청 키의 재사용을 구분 |
| 동시에 두 번 갱신 | 최대 한 번 회전, FE는 한 번만 전송하도록 직렬화 | 경합·철회 범위, 한 성공 응답 뒤 늦은 실패가 세션을 지우지 않게 처리 |

단순 회전안의 재로그인 비용을 숨기지 않는다. 복구 보관안을 채택한다면 새로운 계약 버전에서 응답 유실과 재사용을 구분한 뒤 구현하며, 일반 API의 Idempotency-Key 동작을 인증 endpoint에 자동으로 적용하지 않는다.

204 logout은 refresh 세션 폐기 결과다. 현재 Resource Server처럼 서명/만료만 검사하면 이미 발급된 액세스 토큰은 만료 전까지 유효할 수 있다. 즉시 철회가 필요하면 세션 확인 등 추가 설계를 먼저 확정한다. 제공자 동의 철회·전체 계정 로그아웃·RuFit 데이터 삭제를 이 경로에 묶지 않는다.

실제 잔존 범위는 access 만료와 허용 시계 오차 정책까지 포함한다. FE 문구는 ‘이 기기에서 로그아웃했어요’를 현재 세션에 한정하고, 오프라인/응답 유실 때 서버 폐기 완료로 표시하지 않는다. 안전한 폐기 재시도 증명이 아직 정해지지 않았으므로 서버가 자동 정리됐다고 약속하지 않는다. [문구와 정책 후보](../authentication-adversarial-review.md).

## 실패·응답 유실·FE 저장 연계

| 상황 | HTTP/code 제안 | FE 처리 |
| --- | --- | --- |
| 필수/형식/등록 경로 오류 | 400 VALIDATION_FAILED / INVALID_INPUT | 요청 수정. 입력 원문/토큰은 오류에 포함하지 않음 |
| 잘못된 로그인 증명/비밀 | 401 AUTHENTICATION_REQUIRED | 해당 로그인 시도 종료. 기존 운동 원문 보존 |
| refresh 만료/이미 사용/폐기 | 401 SESSION_EXPIRED(신규) | 재로그인. 운동 큐/백업을 삭제하거나 다른 계정으로 이전하지 않음 |
| 완료된 로그인 시도 재사용 | 409 LOGIN_ATTEMPT_CONFLICT(신규) | 이미 받은 유효 세션이 있으면 유지, 없으면 새 로그인 |
| 요청 제한 | 429 RATE_LIMITED(신규) + Retry-After | 안내된 대기 후 새로운 사용자 조작. 제한 구현은 미완료 |
| 제공자 미설정 | 503 AUTH_PROVIDER_UNAVAILABLE(신규) | 사용 가능하다고 표시하지 않음. 키/설정값은 응답에 노출하지 않음 |
| 키/제공자 일시 장애 | 503 AUTHENTICATION_UNAVAILABLE | 로그인 실패와 구분, 기기 운동은 유지 |
| 회원/세션 DB 장애 | 503 STORAGE_UNAVAILABLE | 저장 성공으로 처리하지 않음 |

예시 application/problem+json:

```json
{"type":"about:blank","title":"Unauthorized","status":401,"detail":"다시 로그인해 주세요.","instance":"/api/v1/auth/refresh","code":"SESSION_EXPIRED"}
```

공통 X-Request-Id는 추적용이며 로그인/refresh 재시도 키가 아니다. 신규 code는 이 초안의 제안값으로, 현재 [오류 계약](../error-contract.md)에 구현돼 있다고 해석하지 않는다.

- 시작 응답을 잃으면 새 시도를 만들고 이전 시도는 만료시킨다. FE는 시도 세대/attemptId가 맞지 않는 복귀를 적용하지 않는다.
- handoff/refresh 응답 유실은 성공 여부가 불명확하다. **이 초안은 토큰 응답 재보관 없는 후보안**이므로 같은 비밀을 무제한 재전송하거나 새 비밀로 성공했다고 간주하지 않고 재로그인한다. 매끄러운 동일 요청 복구를 원하면 별도 보호된 결과 보관 계약을 먼저 확정한다.
- 401을 받은 업무 요청의 인증 갱신과 업무 변경 재시도를 분리한다. 휴식 조절/세트 복제의 이전 변경을 최신 version으로 자동 재적용하지 않는다.
- 로그인 전 기기 기록을 새 계정으로 자동 업로드하지 않는다. /me 확인 후 사용자가 고른 원래 계정 공간을 열고, 가져오기는 기존 명시적 확인 계약을 사용한다.
- logout/계정 전환 즉시 이전 요청의 세대를 무효화한다. 늦게 돌아온 로그인·refresh·운동 응답이 새 계정 저장 공간을 덮어쓰면 안 된다.
- SecureStore 쓰기·삭제·복구 읽기도 authEpoch 검증과 직렬화 대상이다. 이미 시작한 저장/전송은 취소됐다고 가정하지 않는다. 늦은 성공뿐 아니라 오래된 실패/삭제가 새 세션을 지우는 것도 막는다. [후보 facade·저장 순서와 logout 실패 정책](social-login-fe-alignment.md)을 따른다.
- 세션 토큰은 플랫폼 보안 저장소에서 관리하는 FE 구현이 필요하다. 운동 초안 저장소를 토큰 저장소로 사용하지 않는다. 오프라인 로그아웃은 로컬 세션 폐기와 서버 폐기 완료를 구분해 표시한다.

## 구현 전 시험 계획과 검토 완료 조건

| 시험 | 확인할 결과 |
| --- | --- |
| 기존 JWT/소유권 회귀 | 현재 서명·issuer·audience·만료·키 변경/503·다른 owner 차단 유지 |
| 로그인 시도 | 알려지지 않은 provider/client/returnRoute, state/nonce/PKCE 교차 교환 거절, 만료·취소·중복 callback 처리 |
| 계정/프로필 | 같은 issuer+sub는 같은 id, 동일 이메일의 다른 sub/제공자는 자동 병합 안 함, profile404와 신규 가입 구분 |
| 교환/회전 | 동시 요청이 세션/회전을 중복 생성하지 않음, 미완료 트랜잭션의 코드 소비 롤백, 응답 유실 후보 정책 검증 |
| logout/전환 | refresh 폐기/재사용, 액세스 철회 범위, 다른 세션 영향, 늦은 응답과 저장 공간 오염 방지 |
| 외부 제공자/실기기 | 등록된 실제 클라이언트별 aud·redirect·SDK/브라우저 복귀·취소·로그아웃/연결 해제 확인 |

PM이 발급 주체, 진입 흐름, 계정 원장/기존 키 이전, 토큰 수명·응답 유실·철회 범위를 선택하면 v1 계약으로 갱신한다. 이후에만 컨트롤러/DB/테스트 구현을 시작한다. 이번 문서에서는 런타임 또는 DB 시험을 추가 실행하지 않았다.
