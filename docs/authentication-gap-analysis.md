# BE-02 카카오·구글 로그인 연결 점검 v0.1

2026-09-13, PM 검토용 문서. 코드 기준 4a119c49(PR #61), 검증 기준 PostgreSQL 167개 및 CI 34763531710 success. 이 작업은 읽기·계약 초안 작성만 수행하며 실제 로그인이나 운영 설정을 추가하지 않았다. 외부 개발자 콘솔을 열지 않았으므로 사용자가 이미 등록한 앱의 존재 여부는 미확인이다.

## 현재 구현과 공백

| 영역 | 현재 코드에서 확인한 것 | 실제 연결에 남은 것 |
| --- | --- | --- |
| 제공자 | 사용자 선택은 KAKAO·GOOGLE로 확정 | 플랫폼별 실제 등록값·동의 화면·redirect 확인 |
| API 인증 | OidcSecurityConfig: 설정된 단일 issuer/JWKS/audience의 JWT 검증 | 두 소셜 제공자의 로그인 결과를 RuFit API 세션으로 만드는 흐름 |
| 키/장애 | 서명·필수 claim·시간, 새 kid, HTTPS/redirect 제한, 401/503 구분 | 실제 제공자·클라이언트 조합 및 운영 HTTPS 검증 |
| 로그인 시작/완료 | 해당 컨트롤러/콜백 없음. 기본 업무 API 차단 | state/nonce/PKCE 연결, 취소·만료·중복 callback 처리 |
| 계정 식별 | 검증된 issuer+sub를 해시한 owner key, GET /api/v1/me → {id} | 계정 원장·소셜 식별자 매핑, 신규/기존 판정, 이관 계획 |
| 프로필 | owner별 비공개 프로필, 없으면404, 생성/수정/버전 검증 | 프로필 없음과 신규 계정의 구분. 로그인만으로 프로필을 자동 생성할지 결정 |
| 서비스 토큰 | 받은 JWT를 검증, 발급 서버·refresh 저장소 없음 | 발급 주체 선택, 수명·회전·세션 폐기·재로그인 |
| 로그아웃/탈퇴 | 서버 logout·계정 탈퇴 API 없음 | 현재 기기/전체 기기 범위, 제공자 연결 해제, 데이터/삭제 키 보존 정책 |
| 계정 연결 | 이메일로 병합하지 않음 | 본인 확인·명시적 연결·충돌 데이터 이전·연결 해제 조건 |
| FE 인계 | 서버 주소+검증된 /me.id로 저장 공간 분리하는 계약 | 로그인 SDK/브라우저, 보안 토큰 저장, 갱신, 계정 전환과 동기화 상태 연결 |

관련 코드: config/OidcSecurityConfig.java, OidcEndpoints.java, SecurityConfig.java, CurrentAccountController.java, profile/ProfileService.java, UserProfile.java. 현재 DB는 V23/14개 테이블이며 계정 원장·로그인 시도·서비스 refresh 세션 테이블은 없다. 운동·프로필의 ownerName 문자열은 외부 소셜 계정을 등록한 회원 테이블과 같은 것이 아니다.

**소셜 ID 토큰, 소셜 API용 액세스 토큰, RuFit API용 액세스 토큰을 구분한다.** 현재 환경변수만 Google/Kakao 주소로 바꾸면 두 제공자의 실제 로그인이 완성되는 구조가 아니다. 로그인 증명 검증과 서비스 세션 발급은 별도 연결 단계이며, 기존 Resource Server의 서명/소유권 검증을 재개발하지 않는다.

## 공식 문서에서 확인한 연결 조건

아래는 제공자 요구사항이며 RuFit에 설정됐다는 의미가 아니다. 확인일 2026-09-13. 실제 값·비밀은 기재하지 않는다.

| 구분 | 카카오 | 구글 |
| --- | --- | --- |
| 등록/redirect | 사용할 SDK 플랫폼과 앱 키, 로그인 활성화, OIDC 사용 설정. REST code 방식은 등록된 redirect와 client secret 조건 확인 | 사용할 iOS/Android/Web 클라이언트 구분, 동의 설정·앱 식별자·redirect/URL 처리, 백엔드 검증 audience 확인 |
| ID 증명 | issuer는 kauth.kakao.com의 정확한 HTTPS 값. aud는 해당 흐름의 앱 키이므로 네이티브/REST 키를 혼동하지 않음 | ID 토큰의 서명·issuer·audience·만료 검증. 계정은 sub로 찾고 이메일로 찾지 않음 |
| 위조/재생 방지 | state/nonce를 로그인 시도에 연결. 공식 discovery 표에 S256 PKCE 지원 명시 | code 흐름의 state/nonce 및 네이티브 PKCE 연결. 선택한 SDK가 다루는 값과 서버가 검증할 값을 대조 |
| 로그아웃 | 서비스 토큰 폐기, 브라우저 카카오계정 로그아웃, 연결 해제를 별개로 구분 | SDK signOut은 앱의 로그인 상태 정리이며 동의 철회가 아님. disconnect는 별도 연결 해제 |

등록 근거: [카카오 설정](https://developers.kakao.com/docs/ko/kakaologin/prerequisite), [구글 네이티브 OAuth](https://developers.google.com/identity/protocols/oauth2/native-app). 토큰·audience·discovery 근거: [카카오 REST](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [구글 서버 인증](https://developers.google.com/identity/sign-in/ios/backend-auth). state/nonce 근거: [카카오 보안 활용](https://developers.kakao.com/docs/ko/kakaologin/utilize), [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect).

카카오 iOS 문서도 OIDC 설정과 nonce 사용을 설명한다. 다만 이번 문서 검토만으로 선택할 React Native 연결 라이브러리의 실제 전달/반환 동작을 검증한 것은 아니다. [카카오 iOS 로그인](https://developers.kakao.com/docs/ko/kakaologin/ios).

로그아웃 차이의 근거: [카카오 REST 로그아웃·연결 해제](https://developers.kakao.com/docs/ko/kakaologin/rest-api#logout), [Google signOut](https://developers.google.com/identity/sign-in/ios/sign-in#4_add_a_sign-out_button), [Google disconnect](https://developers.google.com/identity/sign-in/ios/disconnect). RuFit의 기록 삭제를 제공자 로그아웃과 같은 동작으로 만들지 않는다.

## PM이 확정해야 할 결정과 영향

| 결정 | 선택지 | 영향/현재 처리 |
| --- | --- | --- |
| RuFit 세션 발급 주체 | A: 두 소셜 로그인을 묶는 별도 OIDC 서비스 / B: RuFit가 소셜 증명을 확인하고 자체 세션 발급 | A는 서비스 선정·운영/비용·Kakao 지원 확인, B는 회원/세션 저장·서명 키·갱신/폐기 운영이 필요. 어느 쪽도 현재 구현하지 않음 |
| 모바일 진입 흐름 | A: 시스템 브라우저 code 흐름 / B: 제공자 네이티브 SDK 결과를 서버에 전달 | A는 서버 callback과 앱 복귀 연결, B는 플랫폼별 SDK·nonce/audience·앱 복귀 동작 검증. FE 담당과 선택 |
| 동일인 계정 연결 | 초기에는 제공자별 분리 유지 / 양쪽 재인증 후 명시적 연결 | 자동 이메일 병합 금지는 확정. 명시적 연결 시 원래 owner key·운동 ID·복구 키·프로필 충돌 처리 필요. 연결 API는 아직 제안하지 않음 |
| 기존 기록 이전 | 기존 owner key 유지 매핑 / 명시적인 검증·이전 절차 | 새 issuer/sub로 바꾸면 현재 해시 ID도 달라짐. 기존 서버/기기 자료를 새 계정에 조용히 합치지 않음 |
| 신규 판정/프로필 | 계정 원장은 생성하되 프로필은 별도 / 로그인 시 프로필까지 생성 | 초안은 별도 프로필 유지안. 현재 profile404만으로 신규 가입을 판정할 수 없음 |
| refresh 응답 유실 | 재로그인 요구 / 같은 요청 키의 짧은 복구 보관 지원 | 초안은 토큰 응답 재보관 없이 재로그인하는 보수적 안. 복구 지원은 보호된 결과 보관·만료·재사용 탐지 정책을 먼저 정해야 함 |
| logout/토큰 수명 | 현재 세션만 폐기 / 전체 세션 폐기, 액세스 만료까지 허용 / 즉시 철회 | 초안은 현재 refresh 세션 폐기. 기존 JWT 검증만으로 이미 발급된 액세스의 즉시 철회는 보장하지 못함. 기간·즉시 철회 필요 여부는 미확정 |
| 탈퇴/보존 | 정책 검토 후 별도 계약 | 계정·운동·프로필·백업·삭제 방지 키·제공자 동의 철회를 함께 정의해야 함. 이번 단위에서 삭제하지 않음 |

자동 계정 연결 금지·현재 데이터 보존은 기존 사용자 원칙이다. 표의 나머지 선택지는 구현 확정이 아니다. [FE용 계약 초안](api-contracts/social-login-draft.md)은 **자체 세션 + 서버 code 중계 후보안**의 검토 자료다. 다른 방식을 선택하면 해당 초안을 먼저 수정하고 버전을 올린 뒤 구현한다.

## 추천안·대안과 결정 주체

아래 추천은 현재 저장소 구조와 제품 요구를 기준으로 한 개발 제안이다. 채택·구현 완료를 뜻하지 않는다.

| 항목 | 추천안 / 대안 | 보안·개발 범위 | 기존 데이터 영향 | 사용자 결정 필요 여부 |
| --- | --- | --- | --- | --- |
| 발급 주체 | RuFit 자체 계정/세션 / 별도 OIDC 통합 서비스 | 자체안은 키·회원·refresh 운영 책임 증가. 대안은 서비스 선정·Kakao 지원·비용/계정 관리 확인 필요 | 어느 안도 기존 owner key 매핑이 필요 | 기술 비교는 PM. 유료 서비스·운영 소유 계정 선택은 구체안 후 사용자 |
| 첫 연결 방식 | 서버 code 중계+시스템 브라우저로 공통 계약 확정 / 제공자별 네이티브 SDK | 중계는 state/nonce/PKCE/callback/handoff 필요. SDK는 플랫폼별 증명 전달/복귀 확인 필요 | 계정 매핑을 동일하게 유지하면 저장 데이터 변화 없음 | 기술 선택은 PM·FE. 로그인 경험을 바꾸는 큰 차이만 사용자 검토 |
| 기존 owner 보존 | 회원 원장에 기존 owner key를 그대로 매핑 / 검증된 이전 작업으로 모든 소유권 키 변경 | 매핑안은 인증 변환기·원장 조회 추가. 이전안은 운동·루틴·프로필·복구 키·오프라인 범위까지 전환 시험 필요 | 추천안은 기존 기록 행과 /me.id 유지 | 기술 매핑은 PM. 서로 다른 계정 데이터 통합은 사용자 제품 결정 |
| 계정 연결 | 기존처럼 제공자별 분리, 명시적 연결은 별도 기능 / 양쪽 최근 인증 후 연결 기능 포함 | 연결안은 유일성·마지막 로그인 수단·프로필 충돌·복구 자료 정책 필요 | 연결을 안 해도 데이터 소실은 없어야 함. 같은 이메일 자동 병합은 계속 금지 | 자동 병합 금지를 재질문하지 않음. 명시적 연결 출시 범위/병합 정책은 사용자 |
| refresh 응답 유실 | 단순 회전 후 유실이면 재로그인 / 같은 요청 키에 한한 짧은 보호된 응답 복구 | 추천안은 비밀 결과 재보관이 없으나 재로그인 증가. 대안은 토큰 결과 암호화·보관 만료·재사용 탐지 구분 필요 | 두 안 모두 운동 큐/기기 백업을 보존 | 일반 기술 정책은 PM. 재로그인 UX/보관 위험을 평가해 필요 시 사용자 |
| logout | 현재 세션 refresh 폐기, FE 즉시 세대 무효화 / 모든 기기 또는 access 즉시 철회 | 즉시 철회는 현재 JWT 검증에 추가 세션 상태 확인 등이 필요 | 운동 기록 삭제와 분리 | 세션 범위는 PM 제안 후 제품 요구 확인. 계정 삭제/통합은 별도 사용자 결정 |

## 기존 issuer+sub 자료를 유지하는 제안

현재 OidcSecurityConfig.ownerKey는 **issuer와 sub에서 만든 해시**를 반환한다. 자체 발급자의 새 JWT에서 sub를 회원 UUID로 바꿔 같은 함수를 그대로 실행하면 owner가 바뀐다. 그 결과 기존 운동이 없어진 것처럼 보일 수 있으므로, 발급자 변경만 먼저 배포하지 않는다.

추천안은 향후 회원 원장에 내부 회원 ID와 변경하지 않을 owner key를 두고, 검증된 소셜 식별자를 원장에 연결하는 방식이다. 기존 자료가 있는 owner key는 그대로 유지하고 업무 API principal 및 /me.id도 해당 key를 사용한다. 자체 서명 토큰의 검증은 기존 검증 기반을 재사용하되 인증 변환기는 신뢰하는 회원 ID를 원장의 owner key로 해석하도록 별도 계약/검증이 필요하다. FE가 보낸 owner/id/email을 이 매핑의 근거로 받지 않는다. 이번 작업에서는 테이블이나 변환기를 만들지 않았다.

- 동일한 신뢰 issuer+sub가 다시 검증되면 그 기존 key의 회원에 매핑한다. 신규인지 여부는 원장을 기준으로 하고 프로필/운동 존재만으로 판정하지 않는다.
- 기존 브로커 issuer+sub와 새 Google/Kakao issuer+sub는 이메일이 같아도 같은 식별자가 아니다. 양쪽 본인 확인 또는 운영자가 검증한 이전 근거 없이 매핑하지 않는다.
- Google 문서가 설명하는 issuer 두 형태를 인정할지와 정규화는 제공자별 검증 정책에서 정한다. 현재 코드의 엄격한 issuer 일치 규칙을 전체적으로 느슨하게 만들지 않는다. [Google claim 규칙](https://developers.google.com/identity/openid-connect/openid-connect#an-id-tokens-payload).
- 카카오 앱/키 구성 변경에 따른 subject 범위를 확인하고, 허용 audience 추가만으로 다른 앱의 기존 자료를 합치지 않는다. 실제 등록값에 대한 확인은 이후 별도 절차다.
- 개발 Basic 자료와 시험 issuer 자료는 실제 소셜 사용자 자료로 자동 이전하지 않는다. 로그인 전 기기 운동은 기존 오프라인 가져오기의 명시적 확인을 거친다.
- 같은 /me.id를 유지해도 API 서버 주소가 바뀌면 FE의 저장 공간 키가 달라진다. 도메인 이전은 로컬 자료/대기 요청을 보존하는 별도 전환 계획이 필요하다.

## 재사용과 단계별 검증

1. 현재 /me·프로필·업무 API와 401/503 계약, issuer+sub 격리·키 교체 시험을 유지한다.
2. PM이 발급 주체·진입 흐름·계정/갱신 정책을 선택하고 FE와 callback/보안 저장 경계를 확정한다.
3. 이후 구현 단위에서 로그인 시도·계정·세션 구조와 마이그레이션을 먼저 명세한다. 기존 owner key와 외부 식별자 관계를 테스트한다.
4. 외부 계정 없이 로컬 제공자·공개 테스트 값으로 서명, nonce/state/PKCE, 중복 callback, refresh 경합/유실, logout, 계정 전환 후 늦은 응답을 시험한다. 현재 이 추가 시험을 실행한 것은 아니다.
5. 실제 제공자 등록값과 동의/redirect를 준비한 뒤 별도 승인된 환경에서 실기기 통합한다. 실제 카카오/구글 사용자 로그인 완료 전에는 로그인 기능 완성으로 표시하지 않는다.

신규 검증 계획의 기준: [OAuth 보안 BCP RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html). 공개 클라이언트 refresh의 회전/송신자 제한과 코드 재생 방지 요구를 구현 선택에 반영해야 한다. 이번 문서 작업에서 비밀 생성·출력, 운영 설정 변경, 로컬 DB 시험은 수행하지 않았다.
