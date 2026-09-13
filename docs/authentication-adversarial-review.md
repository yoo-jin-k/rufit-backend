# BE-02 로그인 초안 적대적 시나리오 검토 v0.2

2026-09-14. PM 요청으로 b5bf784의 문서 초안과 기존 OidcSecurityConfig/ClientAccessConfig/ApiSecurityErrors를 대조했다. **실제 로그인 구현이나 침투시험 결과가 아니다.** ‘명세 보완’은 문서의 빈틈을 채웠다는 뜻이며, 모든 신규 로그인 방어는 구현/시험 전이다. 발급 주체와 흐름은 여전히 PM 검토 중이다.

## 시나리오별 검토 결과

| 공격/실패 | v0.1에서 부족했던 점 | v0.2 명세 보완 | 남은 검증/결정 |
| --- | --- | --- | --- |
| 앱 복귀 코드 가로채기 | 시작 challenge와 handoff의 결합이 서술돼 있었으나 저장/소비 조건이 불명확 | attempt에 challenge/provider/client/복귀 키를 변경 불가로 연결. 교환 때 최초 verifier를 검사, 코드만으로 로그인 불가 | 실제 앱 링크 소유권·가로채기 실기기 시험. PKCE는 verifier 보유 증명이며 기기/정품 앱 신원 자체를 증명하지 않음 |
| 다른 시도 값 섞기 | state/nonce/PKCE가 많아 서로 바꿔 쓸 여지 | 제공자용 PKCE와 앱 handoff용 PKCE를 별도로 정의. callback의 state·issuer/aud·nonce와 교환의 attempt/code/verifier 모두 같은 시도여야 함 | 플랫폼별 코드 흐름과 등록값 대조 |
| 같은 callback 동시 수신 | code를 두 번 외부에 교환할 수 있음 | 시도 상태의 원자적 전이로 외부 교환을 한 번만 시작. 중복 callback은 새 회원/세션 생성 금지 | 외부 응답 유실 후 불명확한 시도 종료, 재시작 복구 시험 |
| 같은 handoff 동시 교환 | 첫 응답 유실 시 두 번째 세션을 발급할 위험 | 비밀/기간 검증 뒤 세션 생성과 handoff 소비를 한 트랜잭션으로 처리. 하나만 성공, 나머지는 재사용 오류 | DB 롤백·프로세스 중단·한 성공의 응답 유실 시험 |
| 잘못된 verifier로 방해 | 잘못된 요청이 정상 코드까지 소비할 수 있음 | 잘못된 검증에서는 코드 소비 금지, 요청량 제한은 별도. unknown code의 오류로 계정 존재 노출 금지 | 제한 범위/수치와 정상 사용자 복구 정책 |
| callback/code·토큰 유출 | 앱 URL의 토큰 금지만 설명 | callback/복귀 응답 no-store, no-referrer, 제3자 리소스 배제. 앱·프록시 로그에서 code/state/handoff/토큰 원문·Location 제거 | 실제 ingress/CDN/모바일 로그 설정 검증. 프로토콜상 callback query에 code가 오는 사실 자체는 없앨 수 없음 |
| 브라우저 저장소/XSS/CSRF | client=WEB을 모바일 JSON refresh와 같은 흐름으로 해석 가능 | WEB은 미확정 예약값으로 분리. 모바일 토큰 body 계약을 웹 localStorage에 그대로 적용 금지 | Web BFF/쿠키·CSRF 또는 별도 SPA 계약을 먼저 결정 |
| 발급자 변경 후 기록 실종 | 새 issuer+sub를 그대로 해시하면 owner 변경 | 기존 owner key 보존 매핑과 전환 검증을 발급자 변경보다 먼저 적용하는 제안 | 회원 원장/이전 증거/기존 브로커 검증·전환 시점 |
| refresh 유실을 탈취로 단정 | 단순 회전의401만으로 원인을 구분할 수 없음 | 유실/이전 토큰 재사용/동시 요청을 분리. 회전안은 유효한 과거 토큰 재사용 시 연결된 활성 refresh도 폐기하는 기준 검토 | 세션=grant/회전 체인 범위 및 보호된 응답 복구 채택 여부 |
| logout 후 access 재사용 | refresh 폐기와 즉시 access 철회를 혼동 가능 | 현재 세션 폐기와 이미 발급된 access 잔존을 구분. 문구는 확인된 결과만 표시 | access 수명·시계 오차·즉시 철회 요구, 오프라인 서버 폐기 재시도 |

앱 복귀/PKCE 기준은 [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html), 코드/refresh 재생 기준은 [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html)을 참고했다. 적용 상태를 보안 구현 완료로 표시하지 않는다.

## 요청 시작 앱과 일회용 코드의 결합

- FE는 시도 시작 전에 verifier를 만들고 해당 시도 세대에만 보관한다. 복귀 URL에서 새로운 verifier나 challenge를 받지 않는다. verifier는 URL·분석 이벤트·운동 초안 저장소에 넣지 않는다.
- BE가 만든 attempt는 provider/client/returnRoute/S256 challenge/만료를 바꿀 수 없게 보존한다. 같은 attemptId로 다른 challenge를 덮어쓰는 업데이트 API를 만들지 않는다.
- callback의 외부 code 검증이 끝나야 그 attempt에 묶인 handoffCode를 만든다. 충분히 임의적인 코드를 사용하고 검증용 값은 안전하게 보관한다. 과거/만료 시도에서 새 코드로 현재 시도를 대체하지 않는다.
- session-exchanges는 attemptId+handoffCode+최초 verifier가 일치할 때만 성공한다. 코드 소비와 회원/세션 저장의 일부만 남으면 안 된다. 정당한 비밀을 갖고 재전송해도 새로운 세션을 다시 발급하는 것은 금지하는 후보안이다.
- 앱은 현재 로그인 시도와 다른 attemptId, 로그아웃/계정 전환 전의 복귀를 적용하지 않는다. 앱이 종료돼 verifier를 잃으면 코드만으로 우회하지 않고 새 로그인한다.
- 서버 세션을 발급했지만 응답을 잃어 FE가 사용하지 못한 세션은 정리 기준이 필요하다. 무조건 다른 기기의 세션까지 폐기하지 않는다. 시도 만료·미사용 세션 보관 정책은 PM 검토 항목이다.

소유권이 확인되는 앱 링크를 우선 검토하며 사용자 지정 scheme을 선택하면 다른 앱의 가로채기와 복귀 URI 검증을 시험한다. 실제 도메인·앱 서명·플랫폼 등록은 외부 의존성이므로 이번 작업에서 설정하지 않았다. PKCE 값 결합만으로 정품 단말 확인이 끝났다고 주장하지 않는다.

## Web 인증은 별도 계약

현재 OIDC는 헤더 Bearer만 받고 서버 세션을 만들지 않으며 CORS credentials=false다. local/test는 Basic과 CSRF를 유지한다. 이 설정에서 쿠키 인증을 추가하면서 전역 CSRF 해제를 그대로 두면 안 된다.

| 안 | 토큰/브라우저 저장 | CORS·CSRF 경계 | 상태 |
| --- | --- | --- | --- |
| Web BFF/서버 세션 추천 후보 | 서비스 토큰은 서버에 보관하고 브라우저에는 Secure·HttpOnly 쿠키 | SameSite·허용 Origin과 모든 쿠키 기반 변경의 CSRF 검증. 교차 Origin이 필요하면 명시 목록만 credentials 허용 | 미확정·미구현 |
| 별도 SPA 후보 | 장기 refresh를 localStorage/sessionStorage에 넣지 않는 별도 설계 필요. 메모리 access/서버 refresh 경계 등 검토 | XSS·다중 탭 갱신·쿠키 사용 여부에 맞춘 CSRF/Origin 계약 | 미확정·미구현 |
| 현재 모바일 후보 | 플랫폼 보안 저장소, Authorization 헤더, refresh 요청의 비밀 | 브라우저 CORS를 모바일 인증 증명으로 취급하지 않음. OAuth state/nonce는 별도 검증 | 초안만 존재 |

CORS 허용은 로그인 증명이나 CSRF 방어의 대체물이 아니다. OAuth 로그인 위조 방지 state와 업무 API의 쿠키 CSRF 토큰도 서로 다른 경계다. 근거: [OWASP CSRF](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html), [OWASP 브라우저 저장소](https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html).

WEB은 초안에서 미래 플랫폼을 표시하는 예약값이며 모바일 교환의 토큰 JSON을 Web에 배포하라는 의미가 아니다. 웹 계약이 확정되기 전에는 해당 등록을 활성화하지 않는 제안이다. 클라이언트 설정·쿠키·허용 Origin·실제 배포 경로는 변경하지 않았다.

## 기존 데이터 전환 시 막아야 할 경우

| 상황 | 처리 제안 |
| --- | --- |
| 같은 신뢰 issuer+sub 재인증 | 기존 원장의 owner key 및 /me.id 유지. JWT 서명 키 교체만으로 ID를 바꾸지 않음 |
| 같은 email, 다른 issuer/sub | 자동 연결/이전 거절. 제품의 명시적 연결 절차 없이는 자료를 합치지 않음 |
| 소셜 로그인에서 새 서비스 issuer로 전환 | 검증된 회원 ID→기존 owner key 매핑 준비 후 전환. 현재 해시 함수를 새 토큰에 그대로 적용하지 않음 |
| issuer 표기 정규화·허용 audience 추가 | 제공자별 검증 규칙과 기존 key 충돌/계정 범위를 사전 대조. 전체 issuer를 느슨하게 인정하지 않음 |
| 회원 원장 없음 + 기존 업무 자료 있음 | profile404를 신규로 처리해 다른 key를 발급하지 않음. 정확한 기존 증명을 확인하는 이전 절차 필요 |
| 서버 주소/계정 전환 | 기존 FE 저장 공간·복구 큐 보존. 새 서버/계정에 자동 업로드하지 않음 |

회원 매핑 유일성·동시 첫 로그인·전환 전후 /me.id·이력/루틴/프로필/삭제 키 접근을 시험해야 한다. 기존 등록 정보를 알 수 없다고 이메일로 대신 추정하지 않는다. 확인 불가능한 기존 자료의 통합은 구현자가 자동 결정하지 않는다.

## 로그아웃 문구와 실제 보장

| 확인된 상황 | 문구 후보 | 보장하지 않는 것 |
| --- | --- | --- |
| 로컬 자격 제거 + 서버 현재 refresh 폐기204 | 이 기기에서 로그아웃했어요. | 모든 기기의 종료, 제공자 동의 철회, 기존 access의 즉시 무효화 |
| 로컬 자격 제거, 서버 응답 유실/오프라인 | 이 기기에서 로그아웃했어요. 서버 세션 종료는 아직 확인되지 않았어요. | 서버 폐기 완료 또는 자동 재시도 완료 |
| 검증된 모든 세션 폐기 기능이 향후 추가됨 | 모든 기기에서 로그아웃했어요. | 현재는 해당 API가 없으므로 사용하지 않음 |

현재 검증기처럼 서명과 만료로 access를 인정하면 logout 후에도 만료 및 허용 시계 오차 범위에서 토큰이 통과할 수 있다. 화면에는 불필요한 토큰 세부사항을 나열하지 않되, 제품 정책/보안 안내에서 즉시 모든 접근을 차단했다고 약속하지 않는다. 즉시 철회가 요구되면 세션 확인·폐기 목록 등 설계와 장애 시 거부 정책을 먼저 정해야 한다.

오프라인 logout 후 서버 재시도를 약속하려면 폐기 요청에 쓸 증명을 어떻게 안전하게 남길지도 결정해야 한다. 활성 로그인 자격을 다시 사용하거나 운동 큐에 refresh 원문을 넣어 해결하지 않는다. Web의 HttpOnly 쿠키 제거/서버 세션 종료도 모바일 로컬 저장소 제거와 동일한 것으로 취급하지 않는다.

## 해결된 설명·남은 결정·외부 의존성

- 명세 보완: 시작 verifier와 handoff의 불변 결합, provider PKCE 분리, 동시 code 교환/세션 발급 방지, 로그/redirect 유출 경계, WEB 예약 상태, 기존 owner 매핑, logout 문구의 보장 범위.
- PM 기술 결정: 발급 주체/모바일 흐름, 시도·토큰 수명과 요청량 한도, refresh grant/체인 범위·재사용 철회와 응답 복구, Web BFF/SPA와 CSRF, access 즉시 철회/오프라인 폐기, 미사용 세션 정리. 모두 구현 전이다.
- 사용자 제품 결정 후보: 유료 인증 서비스·운영 소유 계정, 실제 계정 통합/이전·탈퇴 보존 정책, 모든 기기 로그아웃 범위. 기존 이메일 자동 병합 금지는 유지하며 재확인하지 않는다.
- 외부 의존성: 실제 Kakao/Google 앱·클라이언트·동의·redirect·앱 복귀 도메인/서명 등록, 운영 HTTPS·프록시 로그/비밀 보관, 실기기 SDK/브라우저 검증. 이번 단위는 어느 것도 생성·변경하지 않았다.

검토 후 [초안 v0.2](api-contracts/social-login-draft.md)에 연결 조건을 반영했다. 공격 재현 테스트는 **계획**이며 실행하지 않았다. 문서 검토로 인증 구현·보안 인증·실제 로그인 검증이 완료됐다고 표시하지 않는다.
