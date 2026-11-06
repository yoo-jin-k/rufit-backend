# 이메일 회원가입·로그인 및 소셜 계정 연결 v1

2026-11-05 사용자 결정: 이메일을 아이디로 사용합니다. 실제 메일 서비스는 없으므로 로컬 테스트 메일함부터 검증합니다. **계약이며 현재 API 구현 완료를 의미하지 않습니다.** 기존 social-login-v1 세션·PKCE·토큰 보관 규칙을 계승합니다.

## 사용자 흐름과 보존 기준

- 신규 가입: 이메일 인증 요청 → 메일함에서 인증값 확인 → 이메일 소유 확인 및 비밀번호 등록 → 기존 형식의 Rufit 세션 발급.
- 기존 소셜 회원: 로그인한 상태의 설정 → 로그인 방법 관리 → 이메일 인증 → 비밀번호 등록. 기존 auth_accounts.id 및 owner_key를 유지합니다.
- 이메일 회원의 소셜 연결: 현재 로그인 세션에서 연결 시작 → 카카오/구글 본인 인증 → 같은 세션에서 연결 확정. 일반 소셜 로그인과 연결 목적을 혼용하지 않습니다.
- 이메일 주소가 같아도 자동 병합하지 않습니다. 다른 회원에게 이미 연결된 이메일/소셜 식별자는 연결을 거부합니다. 기존 두 회원의 기록 병합과 연결 해제는 이번 범위에서 제외합니다.
- 세션 응답 account.id, /me, 운동/프로필 소유자는 모두 DB auth_accounts.owner_key를 사용합니다. 소셜 issuer/subject로 계산한 값은 신규 계정 생성에만 사용합니다.
- 로그인 성공 이후의 토큰 저장·계정 전환은 기존 AuthCoordinator 경로에서 처리합니다. 이전 회원의 서버 캐시를 새 회원에게 재사용하지 않습니다.

## API 계약

모든 경로는 /api/v1 기준입니다. Bearer는 Rufit 자체 토큰입니다. 응답은 Cache-Control: no-store이며 비밀값을 URL·로그에 기록하지 않습니다.

| 메서드·경로 | 인증 | 요청 JSON | 성공 |
| --- | --- | --- | --- |
| POST /auth/email/registrations | 없음 | {email} | 202 {requestId,expiresAt} |
| POST /auth/email/registrations/confirm | 없음 | {requestId,verificationToken,password} | 200 기존 SessionView |
| POST /auth/email/login | 없음 | {email,password} | 200 기존 SessionView |
| POST /auth/email/password-resets | 없음 | {email} | 202 {requestId,expiresAt} |
| POST /auth/email/password-resets/confirm | 없음 | {requestId,verificationToken,password} | 204, 기존 모든 세션 폐기 후 재로그인 |
| GET /account/login-methods | Bearer | 없음 | 200 {email: null 또는 주소, providers: [KAKAO 또는 GOOGLE]} |
| POST /account/email-link-requests | Bearer | {email} | 202 {requestId,expiresAt} |
| POST /account/email-link-requests/confirm | Bearer, 요청 시작 세션과 동일 | {requestId,verificationToken,password} | 204, 회원 소유권 유지 |
| POST /account/social-link-attempts | Bearer | 기존 Start(provider,client,returnRoute,codeChallenge,codeChallengeMethod) | 201 기존 Started |
| POST /account/social-link-exchanges | Bearer, 시작 세션과 동일 | 기존 Exchange(attemptId,handoffCode,codeVerifier) | 204, 세션 신규 발급 없음 |

이메일 요청 예: {"email":"member@example.com"}. requestId는 UUID, expiresAt은 UTC ISO8601입니다. 이메일 인증 유효기간은 10분이며 CSPRNG 32바이트의 base64url 43자 verificationToken을 메일로만 전달합니다. HTTP 응답에는 포함하지 않습니다. 메일의 인증값을 앱 확인 입력란에 붙여 넣는 흐름부터 지원합니다. 메일을 열거나 링크를 GET하는 것만으로 가입·연결·비밀번호 변경이 실행되면 안 됩니다.

## 검증과 오류

- 이메일: 앞뒤 공백 제거, 소문자화 후 동일 비교 정책을 가입·로그인·연결·재설정에 적용합니다. ASCII 이메일 254자 이하를 지원하며 +별칭/점 제거 등 제공자별 변환은 하지 않습니다.
- 비밀번호: 15~128자, 공백·유니코드를 허용하며 trim/정규화/자동 잘라내기를 하지 않습니다. 일반적인 유출 비밀번호를 거부합니다. 비밀번호 원문을 저장하거나 메일로 보내지 않습니다. 검증된 단방향 비밀번호 해시와 개별 salt를 사용합니다.
- 이메일 요청은 해당 이메일 기준 3회/15분, IP 기준 20회/시간. 로그인은 이메일+IP 기준 5회/분 및 IP 기준 60회/분입니다. 인증 실패 카운터는 업무 롤백과 별도로 저장합니다.
- 등록/재설정 요청은 계정 존재와 무관하게 같은 202 형식입니다. 기존 회원의 비밀번호를 신규 가입 요청으로 덮어쓰지 않습니다. 인증값 없는 계정 존재 조회 API는 만들지 않습니다.
- 오류는 기존 application/problem+json(type,title,status,code,detail,instance)을 유지합니다. 400 INVALID_INPUT, 401 INVALID_CREDENTIALS(이메일/암호 실패 공통), 401 INVALID_EMAIL_PROOF(만료·오증명 공통), 401 SESSION_EXPIRED, 409 LOGIN_METHOD_CONFLICT, 429 RATE_LIMITED(+Retry-After), 503 MAIL_UNAVAILABLE/STORAGE_UNAVAILABLE를 구분합니다.
- 인증 완료·연결 완료는 인증값 소비와 계정 저장을 한 트랜잭션으로 처리합니다. 중복 완료는 409이며 회원/연결/세션이 중복 생성되지 않습니다. 잘못된 값은 소비하지 않되 시도 제한을 적용합니다.
- 응답을 받지 못한 가입 확정/로그인/비밀번호 변경/연결 확정은 자동 재전송하지 않습니다. 사용자가 로그인 상태를 확인하거나 새 인증을 요청하도록 안내합니다.

## 계정 연결 보안

1. 연결 시작과 확정에서 인증 principal뿐 아니라 세션 ID도 검증합니다. 시작 세션이 만료·폐기되거나 다른 계정/기기로 바뀌면 거절합니다.
2. 외부 계정의 소유 증명은 기존 제공자 토큰 서명·issuer·audience·nonce·PKCE 검증을 그대로 거칩니다. 요청에서 임의 provider subject를 받아 연결하지 않습니다.
3. 일반 로그인 교환으로 연결 시도를 소비할 수 없으며 반대 방향도 거부합니다. 콜백은 연결 목적과 대상 회원을 수정할 수 없습니다.
4. 회원 및 외부 식별자 잠금과 DB 유니크 제약으로 동시 연결 충돌을 처리합니다. 다른 회원에게 연결된 식별자를 이동시키지 않습니다.
5. 이메일 인증 요청은 대상 이메일과 목적, 시작 회원/세션에 고정합니다. 인증값은 해시만 저장하며 완료 후 재사용을 거부합니다.

## 로컬 메일 검증

- 별도 Mailpit 서비스의 SMTP 수신과 웹 메일함을 사용합니다. SMTP와 메일함 포트는 127.0.0.1에만 게시하며 공개 터널에 포함하지 않습니다.
- 테스트 이메일은 로컬 메일함에서만 확인하며 실제 수신자에게 전달되지 않습니다. 실서비스 이메일 인증 완료로 보고하지 않습니다.
- 앱 서버는 SMTP 주소/발신자 설정을 환경변수로 받습니다. 미설정·전송 실패 시 가입 완료로 표시하지 않습니다. 메일함의 인증값을 애플리케이션 로그로 대신 출력하지 않습니다.
- Mailpit 메일/볼륨은 Git에 저장하지 않습니다. 실제 발송 서비스 설정 전 공개 회원가입 배포를 하지 않습니다.

## 검수 순서

1. 기존 소셜 계정의 실제 owner_key를 세션 발급에도 사용하고 두 제공자 로그인에서 동일 기록 접근을 검증합니다.
2. 이메일 중복/정규화, 해시 저장, 오비밀번호·제한, 신규 가입 인증과 만료/재사용을 검증합니다.
3. 비밀번호 재설정 후 이전 access/refresh 폐기와 다른 회원 데이터 보존을 검증합니다.
4. 소셜 회원의 이메일 등록 및 이메일 회원의 카카오/구글 연결을 검증합니다.
5. 연결 목적 혼동, 타인 세션/다른 세션/로그아웃 이후 확정, 동시 요청, 이미 사용 중인 식별자 충돌을 검증합니다.
6. 프론트 가입/로그인/재설정/연결 화면과 오류 재시도, 계정 전환 캐시 분리를 확인합니다.
7. 로컬 메일함의 실제 수신과 iPhone 화면·키보드·로그인 복귀를 수동 검증합니다. 자동 테스트와 별도로 기록합니다.

## 인증한 카카오 계정 확인 (2026-11-06)
- POST /account/social-link-exchanges의 409 LOGIN_METHOD_CONFLICT 응답에 선택 필드 verifiedKakaoMemberId(숫자 문자열, 1~32자)를 추가합니다.
- 기존 Bearer 세션, 연결 시작 세션, handoff, PKCE, 유효기간 검증이 모두 성공한 후의 연결 충돌에서만 제공합니다. 잘못된 인증·다른 세션·일반 가입 충돌에는 포함하지 않습니다.
- 카카오 앱 회원번호이며 이메일/카카오톡 ID가 아닙니다. 클라이언트는 이 차이를 명시합니다. 예외 메시지·로그에는 회원번호를 넣지 않으며 응답은 no-store입니다.
- 재시도·상태 코드·계정 소유권은 기존과 동일합니다. 계정 병합이나 로그인 방법 이동을 실행하지 않습니다.

### 카카오 계정 확인을 이메일로 변경
- 기존 회원번호 힌트를 `verifiedKakaoEmail`(선택, 이메일 문자열 254자 이하)과 `kakaoEmailUnavailable`(선택, true)로 대체합니다.
- POST `/account/social-link-exchanges`의 409 LOGIN_METHOD_CONFLICT에서만, 기존 Bearer·동일 세션·PKCE·handoff·만료 검증 후 응답합니다. Request/성공 응답/재시도 규칙은 유지합니다.
- 서명·issuer·audience·nonce·유효기간 검증을 마친 카카오 ID 토큰의 email만 표시합니다. 계정 병합이나 이메일 기반 소유권 판단에 사용하지 않습니다.
- 이메일이 없으면 unavailable을 반환합니다. 회원번호를 이메일 대신 표시하지 않습니다. 예외 메시지·로그·URL에는 이메일을 넣지 않습니다. no-store 유지.
- 연결 시도에만 이메일을 암호화하여 일시 보관하며 시도 만료 정리 작업으로 삭제합니다. 일반 로그인에서는 보관하지 않습니다.
- 카카오 연결의 account_email 요청은 rufit.social.kakao.email-consent-enabled=true 설정에서만 사용합니다(기본 false). 콘솔 권한 승인 및 동의항목 활성화 후 켭니다. 현재 권한 없음 상태에서는 openid만 요청하여 기존 로그인을 유지합니다.

### 카카오 재인증 화면
- 카카오 인가 URL에 prompt=login을 붙여 계정 재입력을 요청합니다. 이메일 수집 권한과 무관합니다.
- 일반 카카오 로그인과 카카오 연결에 적용하며 Google 요청은 유지합니다. 카카오톡 인앱 브라우저는 제공자 제한으로 지원되지 않습니다.

### 인증 후 기존 계정 통합
- POST `/api/v1/account/social-merge-exchanges`: Bearer 필수. 기존 social-link-attempts로 시작한 동일 세션에서 외부 제공자 인증 후 호출합니다.
- Request: `{attemptId: UUID, handoffCode: string43, codeVerifier: string43..128, confirmMerge: true}`. 이메일/닉네임/대상 accountId를 입력받지 않습니다. 소유권은 제공자 검증 결과로만 결정합니다.
- 204: 현재 계정을 유지하고 인증한 기존 계정의 서버 기록과 로그인 방법을 통합했습니다. 소스 계정의 세션은 전부 폐기하고 대상은 요청 세션만 유지합니다. 클라이언트는 화면 상태 갱신을 위해 다시 로그인하도록 안내합니다.
- 기존 연결 버튼은 자동 통합하지 않습니다. 별도 ‘기존 계정 통합’ 안내를 확인한 사용자가 재인증해야 합니다.
- 세션, PKCE, handoff, 시도 만료와 일회성 소비는 기존 연결과 동일합니다. 성공 재시도는 409 LOGIN_ATTEMPT_CONFLICT이며 재조회로 확인합니다.
- 409 ACCOUNT_MERGE_CONFLICT: 같은 제공자의 다른 계정이 이미 연결됨, 양쪽 이메일 자격, 측정 날짜 중복, 활성 운동 중복, 기타 고유키/관계 충돌. 전체 롤백하며 삭제·덮어쓰기하지 않습니다.
- 운동/루틴/종목/측정/게시물(사진·좋아요 유지)/친구 관계/알림/응원을 이전합니다. 동일 친구·차단·좋아요 관계는 한 건으로 합칩니다. 두 계정 간 직접 관계 및 해석이 필요한 중복 요청은 자동 통합하지 않습니다.
- 현재 프로필·공개 설정을 우선합니다. 원래 양쪽 프로필·설정은 서버 전용 통합 감사 기록에 보관합니다. 공개 범위를 자동으로 확장하지 않습니다. 로컬 전용 기록은 해당 기기의 별도 보관이며 이 서버 통합에 포함되지 않습니다.
- 동일 트랜잭션에서 관련 테이블 쓰기를 잠가 부분 이전을 방지합니다. source 계정 원장은 감사용으로 남깁니다. 감사 정보는 공개 API·로그에 노출하지 않습니다.
