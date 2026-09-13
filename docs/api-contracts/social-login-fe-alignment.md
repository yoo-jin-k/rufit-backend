# BE-02 FE 대조·PM 결정표 v0.1

2026-09-14. **문서 보완 완료, 수치·정책은 PM 채택 전 추천안, 신규 인증 API는 미구현**이다. [로그인 초안 v0.3](social-login-draft.md)에 대한 짧은 보충 문서이며 모바일 서버 code 중계+자체 세션 후보를 기준으로 한다. 기존 계정 ID/자료·DB·보안 설정·프론트 코드는 변경하지 않았다.

대조 자료: [FE PR #41](https://github.com/yoo-jin-k/rufit-frontend/pull/41)의 `docs/social-login-platform-review-v0.2.md`(e89d7b652ce55e03d2a3d5320b857bbee5cea790), 그 문서가 검토한 FE 코드 c7503cf4a19e32458cf27c4002f6e9d08eaa7971. BE 코드 기준은 a482848c7dc73d4d878d8121186bc62931033aca다. FE 검토 작업트리는 문서만 포함하므로 코드 근거는 지정 커밋을 읽었다.

## A01~A07 대응

| ID | 계약 보완 / 판정 | FE·BE 후속 |
| --- | --- | --- |
| A01 | 앱 복귀 `attemptId/handoffCode`와 제공자 callback `code/state`를 구분. 설명 반영 | 시스템 인증 브라우저로 authorizationUrl을 열고 별도 handoff 파서/교환 adapter 사용. 두 PKCE를 혼용하지 않음 |
| A02 | 로그인 시작·교환·refresh·logout의 별도 인증 transport. 설명 반영 | 이 네 JSON 경로는 기존 Bearer를 붙이지 않으며 `credentials:omit`, `redirect:error`. refresh/handoff 비밀 자체는 검증함. 업무 API Bearer 보호는 유지 |
| A03 | `account.id`/`/me.id`: 불투명 문자열, 1~100 UTF-16 code unit, 공백만 있는 값 불가. 길이 불일치 해소안 반영 | FE의 두 기존 제한과 일치. 원문 그대로 비교·저장하며 자르기/trim/재발급/이전 없음. 아래 기존 자료 근거와 검증 한계 참조 |
| A04 | 토큰 교체 후 `/me` 재검증과 새 session facade 생성 필요. 설명 반영 | 같은 서버와 같은 account.id 확인, refresh는 sessionId도 동일해야 함. getter만 바꾸지 않음. 검증·보안 저장이 끝나야 새 facade 공개 |
| A05 | 로그인·refresh·복귀·쓰기·삭제를 공통 authEpoch로 조정. 설명 반영 | 보안 저장 작업도 직렬화하고 시작/완료 때 세대 확인. `close()`가 이미 전송된 요청을 취소한다고 가정하지 않음 |
| A06 | WEB은 별도 인증 계약 예약. 설명 반영 | BFF/쿠키·CSRF·Origin·보관 방식 별도 검토. 제품 전체의 Web 지원 취소가 아님 |
| A07 | 아래 D01~D07 수치와 실패 정책으로 선택지 축소. **PM 결정 대기** | 채택 결과를 v1 계약에 먼저 커밋한 다음 구현. 수치 없는 상태로 FE 인증 연결을 시작하지 않음 |

### A03: 실제 코드와 기존 자료 조건

- [OidcSecurityConfig.ownerKey](../../src/main/java/com/rufit/config/OidcSecurityConfig.java)는 `oidc:` 5자 + SHA-256 hex 64자로 **항상 69 ASCII 문자**를 생성한다. issuer/sub 길이나 한글·보충 평면 문자가 포함돼도 출력 길이는 같다. 이것은 현재 생성식의 사실이며 FE가 69자/접두어를 강제할 계약은 아니다.
- [V2 운동](../../src/main/resources/db/migration/V2__workouts.sql), [V3 루틴](../../src/main/resources/db/migration/V3__routines.sql), [V16 프로필](../../src/main/resources/db/migration/V16__private_profiles.sql) 및 다른 owner 컬럼은 `varchar(100)`이다. PostgreSQL의 문자 수와 JS/Java 문자열의 UTF-16 길이는 비 ASCII에서 다를 수 있다. 현재 OIDC 출력은 ASCII라 둘 다69이며, DB 컬럼만으로 모든 과거 값이 FE 조건을 만족한다고 단정하지 않는다.
- [CurrentAccountController](../../src/main/java/com/rufit/config/CurrentAccountController.java)는 principal 이름을 그대로 반환하며 길이/공백 검증은 현재 없다. 개발 Basic의 임의 사용자 이름까지100자 검증이 구현됐다고 보고하지 않는다. 이번에는 DB를 조회하지 않았으므로 실제 행의 분포/운영 계정 존재를 감사한 결과도 아니다.
- 지정 FE 커밋의 `src/api/session.ts`와 `src/features/workout/connectedStorage.ts`는 `!id.trim()` 또는 `id.length > 100`이면 거절하며 원래 id를 보존한다. 새 계약도 같은 측정/공백 조건이다. **‘불투명’은 형식 해석 금지이지 무제한 길이라는 뜻이 아니다.** OpenAPI의 단순 maxLength만으로 UTF-16 규칙을 표현했다고 간주하지 말고 설명/검증 예제를 함께 둔다.
- 계정 매핑 구현 전 별도 전용 데이터로 69/100/101 UTF-16 길이, 공백만/앞뒤 공백, 보충 평면 문자, 기존 issuer+sub의 동일 id 회귀를 확인한다. 실제 이전이 필요한 환경은 식별자 원문을 노출하지 않는 집계로 예외를 먼저 확인한다. 예외를 발견해도 ID를 잘라 맞추거나 새 키로 자료를 자동 이전하지 않는다.

### A02·A04·A05: FE 인계 순서

1. 인증 JSON transport는 설정된 동일 API 서버의 위 네 경로에만 요청한다. authorizationUrl과 제공자 callback은 이 transport로 fetch하지 않는다. API 경로의 redirect를 따라 다른 서버에 비밀을 보내지 않는다.
2. 교환/refresh 응답을 받은 세대에서, 새 access를 캡처한 **후보 facade**를 만들고 `/me`를 호출한다. 응답 account.id와 정확히 일치해야 한다. refresh는 기존 scope의 서버·계정·sessionId도 일치해야 한다. 로그인에서 다른 계정이 확인되면 기존 scope를 보존하고 별도 공간을 열며 자동 이전하지 않는다. profile404는 계정 판정에 사용하지 않는다.
3. 같은 `{server, accountId, sessionId, authEpoch}`의 refresh는 공유 Promise 하나로 합류한다. 계정 전환/logout은 epoch를 즉시 올리고 이전 facade를 닫는다. 이미 전송된 요청의 부작용 취소는 보장하지 않으므로 늦은 응답은 새 scope에 적용하지 않고 필요 시 원래 scope에서 재조회한다.
4. SecureStore 쓰기/삭제/복구 읽기는 하나의 조정자를 통한다. `{server, accountId, sessionId, refreshToken, refreshTokenExpiresAt, 상태/세대}`를 한 레코드로 다루고 access는 메모리에 둔다. 대기 중 작업은 실행 직전 세대를 검사한다. 이미 시작된 쓰기는 취소된 것으로 간주하지 않고 완료 뒤 검사하며, 다음 세대의 정리/저장이 그 뒤에 실행돼야 한다. 오래된 오류가 새 레코드를 삭제할 수 없다.
5. `/me` 확인 → 보안 저장 성공 → 아직 같은 epoch인지 확인 → 후보 facade 공개 순서다. 중간 실패/다른 계정은 후보를 닫고 동기화를 막는다. 회전 후 저장 실패에 이전 refresh를 되살리지 않는다. 원문 운동·outbox는 그대로 보존한다. 저장 매체 하나의 성공 응답을 네트워크/다른 저장소까지 원자적이라는 보장으로 확대하지 않는다.

## PM 결정표: 수치가 있는 추천안

**모든 숫자는 RuFit 첫 모바일 연결을 위한 공학적 제안이며 RFC/Expo의 의무값이나 현재 운영값이 아니다.** PM은 채택/수정과 이유를 기록한다. D04는 초기 제한이며 공유 IP 오탐·실제 부하를 보고 운영 전 조정한다.

| 결정 | 추천 수치·동작 | 이유 / 선택 시 영향 |
| --- | --- | --- |
| D01 토큰 수명 | access **10분**, 허용 시계 오차 **30초**를 명시. refresh **미사용14일 / 세션 생성 후 절대30일**. 성공한 회전만 미사용 기한 갱신. `refreshTokenExpiresAt=min(회전시각+14일,세션생성+30일)`, access exp는 `min(발급시각+10분,refreshTokenExpiresAt)` | 자주 쓰는 앱의 반복 로그인과 탈취 노출을 절충. 절대기한은 회전해도 연장하지 않음. DB 기한은 `now >= expiresAt` 거절, JWT 오차를 refresh/attempt에 더하지 않음. 즉시 access 철회를 넣지 않는 후보에서는 logout/reuse 뒤 기존 access가 최대 **10분30초** 남을 수 있음. 이를 허용할지 PM 결정; 불허하면 세션 상태 검사/장애 시 거부를 별도 구현 |
| D02 시도·handoff | attempt **5분**, handoff **발급 후60초와 attempt 만료 중 빠른 때**, callback 외부 교환 **1회**, 유효 handoff 소비 **1회**. FE 활성 attempt **1개/앱 인스턴스** | 브라우저 동의 시간을 주되 앱 복귀 비밀 노출 기간 축소. 새 시도가 이전 서버 시도를 무단 취소하지 않음. 만료 후 새 로그인, 도중 응답 유실도 code-only 우회 없음. 앱당1개는 FE 규칙이며 서버가 기기 신원을 검증했다는 의미가 아님 |
| D03 크기·형식 | RuFit access **1~4096 ASCII 문자**(최소 claim JWT 후보), refresh/handoff 각각 CSPRNG **32바이트 → padding 없는 base64url43자**, attemptId/sessionId UUID **36자**. verifier43~128 허용문자/S256 challenge43자는 기존 계약 유지. 네 인증 POST의 JSON body **2048 UTF-8바이트**, 인증 JSON 응답 **16384바이트** 상한 제안 | 짧은 opaque refresh로 보안 저장 부담을 줄임. 공급자 토큰의 크기와 섞지 않음. 헤더의 `Bearer `7자를 더한4103바이트 및 전체 헤더는 프록시/서버에서 별도 검증. body 초과413, 형식400 제안. access를 SecureStore에 함께 넣지 않음. 기존 예제의 `example-*`는 모양 설명용이며 선택 후 경계용 fixture로 분리 |
| D04 시도·요청 제한 | 서버가 확인한 IP 기준 로그인 시작 **20회/60초 + 100회/1시간**, 미완료 attempt **30개/IP**. 나머지 인증 POST **120회/60초/IP**, 잘못된 handoff 증명 **5회/60초/(attempt,IP)**. 확인된 세션 refresh **12회/60초/sessionId**. 이동 구간 기준, 초과429 + 다음 허용까지 올림한 정수초 Retry-After | 초기 폭주/추측 시도와 정상 중복을 제한. 틀린 verifier만으로 attempt를 소비/영구 폐기하지 않음. 알 수 없는 refresh로 타인의 session 제한/철회를 변경하지 않음. logout은 refresh 전용 한도를 공유하지 않음. 신뢰 프록시에서 얻은 IP만 사용, 임의 X-Forwarded-For 금지. 다중 서버 공유 카운터/만료와 NAT 오탐 시험 필요; 설치ID를 인증된 단말로 취급하지 않음 |
| D05 재사용 철회 | **1 sessionId = 1 grant = 1 refresh 회전 체인**. 유효했던 과거 refresh 재사용 시 그 체인의 활성 refresh까지 원자적으로 폐기,401 SESSION_EXPIRED. 동시2개는 최대1회 회전하지만 두 번째 재사용으로 첫 응답의 새 refresh도 폐기됨. 다른 기기/새 로그인 세션은 유지 | 탈취자가 먼저 회전한 경우도 갱신을 중단. 이전 토큰 검증용 해시와 관계를 해당 grant 절대만료+30초까지 유지, 원문은 DB에 보관하지 않음. 알려지지 않은 난수는401만 반환. logout은 알려진 이전/현재 refresh로 같은 체인을 폐기할 수 있고 이미 폐기/알 수 없는 정상형식 비밀에는 동일204 제안. 계정 존재를 노출하지 않음 |
| D06 응답 유실 | handoff/refresh의 처리 여부 불명확 후 **자동 재전송0회**, 재로그인. 첫 연결은 토큰 응답 복구 보관/유예 재사용 **0초** | timeout·Abort·네트워크 오류·JSON 손상은 미전송 증거가 아님. 원문 운동/outbox/같은 계정 scope 보존. 새 로그인은 기존 기록을 지우지 않음. 더 매끄러운 복구가 필요하면 별도 요청 키·암호화 응답 보관 계약으로 확대. `/me` 읽기 재시도와 비밀 재사용을 구분 |
| D07 logout 미확인 | 즉시 epoch 무효화·API 차단, 보안 저장 정리. 서버 결과 UI 대기는 **5초**, 이후 상태 ‘서버 종료 미확인’. 서버 재시도용 비밀의 영구 보관/자동 재시도 **0회**. 첫 연결에서는 **앱 재시작 시 자동 세션 복원을 후속 단위로 보류** | 로컬 삭제 성공+서버204만 일반 완료. 로컬 삭제 성공+유실/오프라인은 미확인 문구. 삭제 실패는 로컬 완료도 표시 금지, 현재 프로세스는 계속 차단하고 정리 재시도. 지속 표식·삭제 모두 실패하면 재시작 뒤 logout 의사를 복구했다고 보장할 수 없어 자동 복원 출시 전 검증 필요. 서버 미확인이면 refresh가 남을 수 있고 탈취자가 회전하면 절대30일까지 지속할 위험도 PM이 검토 |

수명 원칙/회전 관계와 활성 토큰 철회는 [RFC 9700 §4.14.2](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2)를 근거로 했다. RFC는 위14일/30일을 정하지 않는다. 서버의 미사용/절대기한 구분은 [OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#session-expiration)를 참고하되 웹 세션의 예시 시간을 모바일 refresh에 그대로 대입하지 않았다. 크기/빈도 검증과 JWT logout 잔존은 [OWASP REST Security](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html)를 참고했다.

[Expo SecureStore](https://docs.expo.dev/versions/latest/sdk/securestore/)는 플랫폼의 큰 값 거절을 처리하라고 설명하며 보편적인2048바이트 상한을 보장하지 않는다. D03의2048은 **RuFit 요청 body 제안**이다. 저장 레코드는 별도 실측하고 읽기/쓰기/삭제 오류·재설치·백업 복구를 시험한다. 크기가 작아도 저장 성공을 보장하지 않으며 실패 시 AsyncStorage 평문 토큰으로 우회하지 않는다.

logout 문구 후보: 로컬 정리/서버204 ‘이 기기에서 로그아웃했어요.’; 로컬 정리/서버 미확인 ‘이 기기에서 로그아웃했어요. 서버 세션 종료는 아직 확인되지 않았어요.’; 로컬 정리 실패 ‘로그아웃을 완료하지 못했어요. 다시 시도해 주세요.’ 마지막 경우에도 현재 프로세스의 API 사용은 막는다. 어느 경우도 운동을 삭제하거나 제공자 동의를 철회하지 않는다.

## 남은 결정과 구현 순서

| 순서 / 제안 작업 | 실제 등록값 없이 가능한 구현·모의 검증 | 운영 등록 의존 / 시작 조건 |
| --- | --- | --- |
| 0. 계약 v1 | PM의 모바일 code+자체 세션 선택, A03 및 D01~07 채택값을 기록. FE에 endpoint/request/response/오류·상태 전달 | 이번 산출물. 아직 채택 전이며 신규 구현 착수 아님 |
| 1. 계정 연속성·시도 | 계정/소셜 매핑·attempt/세션 ERD와 마이그레이션을 먼저 명세. 고정 시계, 가짜 제공자/JWKS로 100자 경계·기존69자 보존·동시 첫 로그인·state/nonce/두 PKCE·만료/제한 검증 | 기존 키의 검증된 매핑 정책 필요. DB 통합 시험은 PM과 전용 DB 사용 일정 조율. 실제 계정 자료를 자동 이전하지 않음 |
| 2. code/handoff·자체 발급 | callback 외부 요청1회, 동시 교환1회, 발급/소비 롤백, 응답 유실·프로세스 중단, no-store/비밀 없는 오류·로그, `/me` 일치. FE는 모의 JSON transport/후보 facade/epoch·직렬 저장 실패 시험 가능 | 모의 서명 키만 사용. 실제 카카오 REST·구글 Web Server OAuth 등록과 키/동의/redirect, 앱 복귀 scheme/도메인·서명은 별도 준비 |
| 3. 회전·logout | 체인 재사용/동시성, 이전 해시 보존, 모르는 토큰의 타인 영향0, 5초 응답 유실, access 잔존 상한, 저장 실패/삭제 역전·다른 계정 보호 | 채택한 즉시 철회 여부와 D07 실패 정책 검증. 미확인 상태에 성공 표시 없음 |
| 4. 실제 모바일 연결 | 위 시험을 통과한 개발 빌드에서 FE와 연결; 실제 제공자 검증 전까지 ‘모의 연결 완료’로만 표기 | iOS/Android 등록·앱 링크 소유권·서명·실제 audience/nonce/로그 필터와 정상/취소/cold start 실기기 시험. 사용자 소유 계정·비용·도메인은 임의 생성/선택하지 않음 |
| 5. 후속 범위 | 앱 재시작 자동 복원, Web 인증, 명시적 계정 연결·탈퇴는 각각 별도 계약 | 자동 복원은 SecureStore 실패/프로세스 중단 시 재로그인·logout 의사 보존을 검증한 후. Web은 별도 지원 작업으로 예약 |

수치/동작은 PM 기술 결정이고, 유료 서비스·운영 소유 계정·도메인 구매·서로 다른 계정 자료 연결은 별도 사용자 결정이다. 이 문서는 새 API 실행, 모의 인증 테스트 통과, 실제 제공자 등록, 운영 보안 검증 완료를 의미하지 않는다.

이번 확인: 관련 문서7개의 JSON 예제5개 파싱·내부 링크59개 확인, 공개 PKCE 예제 쌍·합성 OIDC 키69자·UTF-16 경계 계산, A01~A07/D01~D07 누락 검사, diff 공백 검사. 로컬 DB/신규 인증 런타임 시험은 실행하지 않았다.
