# FE 소셜 로그인 통합용 실행 안내

2026-10-29. **실제 Spring API + PostgreSQL + fake OAuth/JWKS**다. 실제 Kakao/Google 앱 등록, 로그인 동의 화면, 실기기 딥링크 검증을 대신하지 않는다. FE 코드/서버8099는 수정하지 않는다.

## 접속과 자원

| 항목 | 값 |
| --- | --- |
| API base | `http://127.0.0.1:8096` |
| health | `/actuator/health` |
| Google fake 승인 | 시작 API가 반환한 `/google/authorize?...` URL 그대로 사용 |
| Kakao fake 승인 | 시작 API가 반환한 `/kakao/authorize?...` URL 그대로 사용 |
| callback | `/api/v1/auth/callback/google`, `/api/v1/auth/callback/kakao` |
| 앱 복귀 | `rufit://auth/callback?attemptId=...&handoffCode=...` |
| CORS 시험 origin | `http://localhost:8099`, `http://127.0.0.1:8099`, credentials 없음 |
| API 컨테이너 | `rufit-auth-preview` |
| 전용 DB 컨테이너/볼륨 | `rufit-auth-preview-db` / `rufit-auth-preview-pg` |
| DB/role | `rufit_test`; DB 포트는 호스트에 공개하지 않음 |
| 자동 회귀 DB | 별도 `rufit-auth-test-db`/host5437. preview를 테스트 초기화 대상으로 사용하지 않음 |

사용 중인 BE 작업 공간에서 실행한다. 110종 갱신 후 현재 실행 작업공간은 `rufit-backend-catalog`다. 로그인 이전 작업공간은 rufit-backend-auth이며 preview를 그 이전 코드로 다시 시작하지 않는다.

```sh
python3 scripts/fake-login-preview.py start
python3 scripts/fake-login-preview.py status
python3 scripts/smoke-social-login.py
python3 scripts/smoke-exercise-catalog.py
python3 scripts/fake-login-preview.py stop
```

- Docker가 필요하다. 공식 PostgreSQL17, Maven/Java21 이미지로 실행하며 사용자 시스템 Java 버전과 무관하다. 시작 시 필요한 의존성을 받을 수 있다.
- `start`는 다른 소유권 라벨의 컨테이너/볼륨을 덮어쓰지 않는다. 이미 동작 중이면 그대로 둔다. 코드 갱신을 반영하려면 BE 담당과 시험 중단을 조율한 뒤 stop/start한다.
- `stop`은 preview의 API/DB만 멈추고 DB 볼륨을 보존한다. 자동 회귀 DB, 기존 FE5435/8099, 다른 컨테이너를 종료하지 않는다.
- 임시 환경 파일에는 자동 생성한 시험 DB 암호만 들어가며 파일 권한600이다. 실제 서비스 키를 사용하지 않는다. 파일 내용/토큰을 출력하거나 Git에 추가하지 않는다.
- 기동마다 새로운 시험 JWT/AES 키를 만든다. 재기동 전에 발급한 access는 무효이므로 새 로그인한다. 운영 키 회전/영구 저장의 구현 증거가 아니다.
- 이 런처와 fake 제공자는 `src/test`에만 있고 운영 JAR에는 없다. preview는 호스트 loopback8096에만 공개한다.

## fixture와 HTTP 확인 순서

1. FE에서43~128자 verifier를 만들고 S256 challenge로 `POST /auth/login-attempts`한다. provider는 GOOGLE/KAKAO, client는 IOS/ANDROID, returnRoute는 APP_LOGIN이다.
2. authorizationUrl을 시스템 브라우저에 연다. fake는 비밀번호 입력 없이 `fixture-alice`를 승인한다. 독립 계정은 **fake 승인 URL에만** `&fixtureUser=bob`를 붙인다. 다른 provider의 같은 fixture 이름은 다른 계정이다.
3. provider 승인→서버 callback→등록 앱 URI의302를 따른다. 앱 복귀에서는 attemptId/handoffCode만 읽고 보관한 FE verifier와 교환한다. provider code/state를 교환 입력으로 쓰지 않는다.
4. `POST /auth/session-exchanges`의 새 access로 `GET /api/v1/me`를 확인한다. owner 키는 불투명 원문으로 취급한다.
5. `POST /auth/refresh`는 refreshToken 하나를 받아 새 토큰 쌍을 반환한다. sessionId/account.id는 같다. 성공한 이전 refresh를 다시 보내면 그 세션 전체가 폐기된다.
6. `POST /auth/logout`은204이며 같은 세션의 기존 access로 /me를 다시 부르면401이다. 다른 세션은 유지한다.

위 `/auth/...`는 모두 `/api/v1/auth/...`다. 응답 유실 시 handoff/refresh 자동 재전송은0회다. 본문/응답 형식·기한·429·실패 정책은 [v1 계약](api-contracts/social-login-v1.md)을 따른다.

Web client 로그인 계약은 예약 상태다. 브라우저 preview에서 custom scheme 복귀가 자동 동작한다고 간주하지 않는다. Node/Python의 수동 redirect HTTP 시험 또는 시스템 브라우저/딥링크 어댑터에 handoff를 전달하는 통합 fixture로 먼저 검증하고, 실제 앱 등록·복귀는 별도 실기기 시험한다. `smoke-social-login.py`는 redirect를 수동 처리하여 전체 HTTP 흐름, 계정 구분, 갱신/재사용 폐기/로그아웃/동시 갱신을 확인한다. 이 스크립트가 만든 세션만 로그아웃하며 FE 데이터/다른 세션을 초기화하지 않는다.

결과 파일 `target/fake-login-http-result.json`에는 상태/검사항목만 저장한다. 토큰/비밀/개인정보는 저장하지 않는다. OpenAPI는 활성 access로 `/v3/api-docs`에서 확인하거나 CI `backend-api-contract`의 `auth-openapi.json`을 사용한다.

2026-10-29 PM/FE 조율 후 PR67 구현9964ac0으로 갱신했고 V26/110종/검색키240개 및 데이터 보존·새 로그인 smoke를 확인했다. FE 최초 로그인 시험의 중단 실행에서 fake grant1개가 남았을 수 있으나 안전한 식별 근거가 없어 추정 삭제하지 않았고, 전용fixture 만료/정리 대상으로 둔다. 재기동은 키가 바뀌므로 기존 token 재사용은 불가다.
