# 인증 실행 방식

## 현재 기본값

프로필 없이 실행하면 health만 공개하며 업무 API는 403이다. `local`/`test`는 개발용 Basic 인증과 CSRF를 사용한다. 실제 회원 로그인으로 취급하지 않는다.

## OIDC 액세스 토큰 검증

`oidc` 프로필은 로그인 제공자가 발급한 RS256 서명 JWT를 검증한다. 실행 전 아래 환경변수를 설정한다.

- `SPRING_PROFILES_ACTIVE=oidc`
- `RUFIT_AUTH_ISSUER`: 신뢰하는 발급자의 정확한 issuer URL
- `RUFIT_AUTH_JWK_SET_URI`: 해당 발급자의 공개키 JWKS URL
- `RUFIT_AUTH_AUDIENCE`: 루핏 API에 등록한 audience
- 기존 DB_URL, DB_USERNAME, DB_PASSWORD

클라이언트는 `Authorization: Bearer <access_token>`으로 호출한다. 쿠키, Basic 인증, URL 파라미터 토큰은 사용하지 않는다. 서버 세션을 만들지 않으며 이 프로필의 헤더 토큰 요청에는 CSRF 토큰이 필요하지 않다. local/test의 CSRF 보호는 유지한다.

서명, issuer, 만료·사용 가능 시각, audience를 검사하고 sub 및 exp가 없는 토큰을 거절한다. 검증 실패는 401 AUTHENTICATION_REQUIRED이고 상세 검증 사유나 토큰을 응답에 노출하지 않는다. health는 공개한다.

소유권 키는 issuer와 subject를 함께 SHA-256으로 변환한 내부 식별자다. 이메일과 표시 이름은 소유권 판단에 사용하지 않는다. 이메일이 같아도 다른 subject는 별도 사용자이며, 로그인 제공자가 바뀌어도 기존 사용자로 자동 합쳐지지 않는다. 계정 연결은 별도 본인 확인 정책과 데이터 이전 기능이 필요하다. 개발용 Basic 기록도 운영 계정으로 자동 이전하지 않는다.

## 검증과 남은 연결

새 `kid`의 서명 키를 제공자가 공개하면 키를 다시 조회하여 같은 issuer/subject의 기존 기록에 접근합니다. 키가 바뀌었다고 계정을 새로 만들지 않습니다. 로컬 제공자 시험으로 기존·새 키의 전환, 타 계정 접근 거절, 키 서버의 일시 중단과 복구를 확인합니다.

새 키를 가져오지 못하는 인증 서비스 오류는 503 `AUTHENTICATION_UNAVAILABLE`이며 토큰이 잘못된 경우의 401과 구분합니다. 이 응답은 로그인 재입력을 요구하는 WWW-Authenticate 헤더를 넣지 않고 상세 원인·키 주소를 반환하지 않습니다. 검증되지 않은 요청은 업무 저장으로 진행하지 않습니다. 앱은 대기 중 변경을 보존하고 연결 회복 후 최신 상태와 같은 작업의 처리 결과를 확인해야 합니다. 라이브러리 키 캐시는 유지하며 키 제거만으로 이미 발급한 토큰의 즉시 철회를 보장하지는 않습니다.

테스트마다 임시 RSA 키와 로컬 JWKS 서버를 생성하여 실제 서명 토큰의 DB 쓰기와 사용자 격리를 검증한다. 위조 서명, 잘못된 issuer/audience, 만료·미래 토큰, 누락된 필수 claim을 거절하는 테스트를 포함한다. 실제 로그인 제공자 계정이나 비밀키는 테스트에 사용하지 않는다.

**로그인 제공자는 카카오·구글로 확정했다. 실서비스 클라이언트 등록·모바일 로그인 화면·PKCE 흐름·토큰 갱신·회원 탈퇴/계정 연결은 아직 구현하지 않았다.** 이 브랜치는 백엔드 토큰 검증 기반이며 자체 토큰 발급 서버가 아니다. 운영 공개 전 실제 제공자와 HTTPS 환경에서 통합 검증해야 한다.

근거: [Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [Spring Boot OAuth2](https://docs.spring.io/spring-boot/reference/security/oauth2.html), 루핏 기획의 로그인 필수·동일 이메일 자동 병합 금지 정책.

## 클라이언트 계정 확인과 웹 연결

`GET /api/v1/me`는 인증된 계정의 안정적인 `id`만 반환합니다. OIDC에서는 검증한 issuer/subject로 만든 서버 소유자 키입니다. 이메일, 토큰, 이름은 반환하지 않습니다. 클라이언트는 서버 주소와 이 ID를 함께 사용해 오프라인 저장 공간을 계정별로 구분해야 합니다. 토큰 갱신으로 ID가 바뀌지 않으며, 다른 subject는 같은 이메일이어도 별도 ID입니다.

웹 화면 연결이 필요하면 `RUFIT_ALLOWED_ORIGINS=https://app.example.com,http://localhost:8081`처럼 정확한 origin을 지정합니다. 기본값은 비어 있어 교차 출처 브라우저 요청을 허용하지 않습니다. 와일드카드, 경로, 끝의 `/`, 인증 정보, 쿼리는 허용하지 않습니다. GET/POST/PUT과 사전 요청 OPTIONS를 허용하며 Authorization, Content-Type, Idempotency-Key, X-CSRF-TOKEN 헤더를 받을 수 있습니다.

OIDC에서는 쿠키 자격 증명을 허용하지 않습니다. local/test에서는 CSRF 세션을 사용하는 개발 클라이언트를 위해 등록된 origin의 쿠키를 허용합니다. CORS 허용은 인증 허용이 아니며 실제 API 요청에는 여전히 인증이 필요합니다. 네이티브 앱의 HTTP 요청에는 브라우저 CORS가 적용되지 않습니다.

## 발급자와 검증 키 주소 검사

JWKS 조회는 설정한 주소의 redirect를 따라가지 않습니다. 제공자가 주소를 옮겼다면 신뢰할 최종 HTTPS 주소를 직접 설정해야 합니다. 연결과 읽기 대기는 각각 5초로 제한합니다. 키를 가져오지 못하면 해당 토큰을 인증하지 않으며, 이미 검증된 키의 라이브러리 캐시 동작은 유지합니다. 로컬 HTTP 테스트에서도 다른 경로로 이동하라는 응답을 따라가지 않고 정상 최종 주소의 키는 검증하는지 확인합니다.

OIDC 프로필 시작 시 issuer는 호스트가 있는 HTTPS 주소이고 query/fragment/사용자정보가 없는지 검사합니다. 문자열을 자동 정규화하지 않으므로 토큰의 issuer와 설정값은 기존처럼 정확히 일치해야 합니다. JWKS는 HTTPS를 기본으로 요구하며 사용자정보·fragment·유효하지 않은 포트를 거절합니다. 잘못된 설정 오류에는 입력 주소를 그대로 출력하지 않습니다.

실제 서비스 설정에는 HTTPS JWKS를 사용합니다. 테스트용 로컬 서명 키 서버는 `RUFIT_AUTH_ALLOW_LOCAL_HTTP_JWKS=true`를 명시한 경우에만 `localhost`, `127.0.0.1`, `[::1]`의 HTTP 주소를 허용합니다. 이 옵션도 원격 HTTP나 사설망 HTTP는 허용하지 않습니다. 자동 테스트는 자기 프로세스로 만든 로컬 키 서버에만 이 옵션을 사용하며 운영 Compose에는 옵션을 추가하지 않습니다.

근거: [OpenID Connect Discovery의 발급자 메타데이터 규정](https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata). 실제 로그인 제공자 등록·로그인 화면은 별도 작업이며, 이 검사는 주소 설정의 형식과 전송 방식만 확인합니다.
