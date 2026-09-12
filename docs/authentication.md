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

테스트마다 임시 RSA 키와 로컬 JWKS 서버를 생성하여 실제 서명 토큰의 DB 쓰기와 사용자 격리를 검증한다. 위조 서명, 잘못된 issuer/audience, 만료·미래 토큰, 누락된 필수 claim을 거절하는 테스트를 포함한다. 실제 로그인 제공자 계정이나 비밀키는 테스트에 사용하지 않는다.

**아직 로그인 제공자 선정·실서비스 클라이언트 등록·모바일 로그인 화면·PKCE 흐름·토큰 갱신·회원 탈퇴/계정 연결은 구현하지 않았다.** 이 브랜치는 백엔드 토큰 검증 기반이며 자체 토큰 발급 서버가 아니다. 운영 공개 전 실제 제공자와 HTTPS 환경에서 통합 검증해야 한다.

근거: [Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [Spring Boot OAuth2](https://docs.spring.io/spring-boot/reference/security/oauth2.html), 루핏 기획의 로그인 필수·동일 이메일 자동 병합 금지 정책.
