# 백엔드 개발 현황

2026-10-29 현재 구현 기준. 운영 배포 완료를 뜻하지 않는다.

| 단위 | 구현·검증 | 다음/한계 |
| --- | --- | --- |
| 기존 운동·루틴·이전 기록·세트/휴식·저장복구 | PR61까지 기존167시험 기반 보존 | 이전 기능 PR은 미병합 |
| 로그인 계약 | PR63/f95ff9ce, 모바일 브라우저+서버 중계+자체 세션 | [v1 계약](api-contracts/social-login-v1.md)이 최신 기준 |
| 계정·세션 기반 | PR64/b21615c7, sid DB 활성 검증/DB오류503, 기존 owner·oidc 유지 | 실제 제공자 등록 미완료 |
| 코드 교환/handoff | PR65/eee22d5, 시작201·callback302·교환200, PKCE/state/nonce·원자 저장 | provider/JWKS는 fake HTTP로 검증 |
| refresh/logout | codex/auth-session-lifecycle, 회전·재사용 grant 폐기·즉시 access 거부·정리 | [구현·검증](auth-session-lifecycle.md), developer 대상 draft PR 제출 단위 |
| 전체 회귀 | Java21/PostgreSQL17 Maven verify196개, 실패0/오류0/건너뜀0, 패키징 성공 | 전체 시험 DB는 독립5437 |
| FE 통합 fixture | 실제 API8096 + 별도 preview DB, Google/Kakao fake 흐름·갱신/폐기·동시 요청 smoke 통과 | [실행 안내](fake-login-integration.md), FE 검증 결과는 별도 |
| 운동 목록 확장 | 계약afa42b3→V26, 공용110종/검색키240개, 기존18/기록/루틴/개인휴식 보존 | 전체202시험/패키징 통과. [v2 계약](api-contracts/exercise-catalog-v2.md) |

main/developer는 변경·병합하지 않는다. developer에서 만든 작은 codex 브랜치의 누적 의존성을 PR에 명시한다. FE 저장소/파일/서버8099와 인계DB5435는 변경하지 않는다. PM 전달은 사용자가 직접 승인했다. sources와 공통 coordination/status.md는 수정하지 않는다.

GitHub CI는 계정 결제/사용 한도 사유로 job 시작 전에 차단됐다(PR65 run40740135583 annotation 확인). 로컬 검증 통과를 CI 통과로 표현하지 않는다. FE URI 정합화 후 관련22개도 통과했다.

현재 Flyway V26, 앱 테이블21개. [DB 관계](database-erd.md), [폴더 구조](backend-structure.md)를 참조한다. 운영 DB는 미생성/미배포이며 개발용 테이블은 마이그레이션이 관리하므로 수동 생성할 필요가 없다.

남은 외부 연결: 실제 Kakao/Google 등록/실키·실기기 브라우저/딥링크, 운영 도메인/TLS·신뢰 프록시·접속 IP·로그·키 관리, 운영 PostgreSQL/백업·복구 운영화. FE 보안 저장·로그아웃 미확인·재시작 복원은 서버 시험만으로 완료로 표시하지 않는다. 이메일 자동 계정 병합과 유료 운동 미디어 구매/전체 교체는 하지 않는다.

## 2026-09-14 이전 기록

아래 내용은 당시 단계의 이력이다. 로그인 미구현/수치 미확정/추가 DB시험 대기 등의 당시 설명은 위2026-10-29 구현 기준으로 대체되었다.


2026-09-14 기준. 이 문서는 실제 구현·검증과 남은 작업을 구분한다.

## 담당 범위와 브랜치

- rufit-backend만 담당한다. Spring Boot API·인증/인가·PostgreSQL·JPA·비즈니스 규칙·입력 검증·예외처리·테스트·명세를 관리한다.
- 사용자 요청에 따라 developer 기준 기능 브랜치에서 작업하고 developer 대상 PR을 만든다. 원격 developer가 없어 기존 main/develop과 같은 초기 커밋 2e6b193에서 생성했다. main/develop은 수정하지 않았다. 새 작업은 PM 기준 codex/<기능>-<작업>으로 분리하며 현재 진행 feature/*는 보존한다.
- 이전 develop 대상 기능 PR은 아직 미병합이다. 의존 기능을 포함하는 PR은 누적 범위와 선행 PR을 밝힌다. 사용자 확인 없이 기준 브랜치로 병합하거나 운영에 배포하지 않는다.
- 새 기능은 docs/api-contracts에 요청·응답·오류·재시도 규칙을 먼저 확정해 커밋한 뒤 구현한다. 기능마다 쉬운 한국어 주석을 단다.

## 구현과 검증

| 작업 | 구현 | 확인한 범위 |
| --- | --- | --- |
| 운동시간 정지/진행 | POST /api/v1/workouts/{id}/pause, /resume | 명시적 정지만 시간을 멈춤, 종료 시 정지 시간 제외. PR #58 |
| 종목별 휴식 | GET/PUT /api/v1/exercises/{id}/rest | 개인값(0초 포함) → 종목 추천 → 공통 기본값, 소유권·버전 검사. PR #59 |
| 현재 휴식 조절 | POST /api/v1/workouts/{id}/rest/adjust | ±10초, 만료 후 재시작, 0~3600초 제한, 중복 적용 방지. developer 대상 PR #60 |
| 입력한 세트 복제 | POST /api/v1/workouts/{id}/sets/{setIndex}/duplicate | 계약 97ec685 먼저 공개, 구현 4a119c49 / developer 대상 PR #61. 로컬167개 및 CI 통과. [요청·응답](api-contracts/set-duplication.md) |
| 기존 주요 API | 운동·루틴·이력·통계·기록 정정/삭제·오프라인 가져오기 | 사용자 격리, 동시 수정, 응답 유실·재시작·DB 장애/복구 시험 |

휴식 조절 구현까지 전용 PostgreSQL에서 Maven verify 160개 테스트가 통과했다. 이후 DTO 명세와 주석 보완 후 API 계약·휴식 조절 관련 6개 테스트도 통과했다. 서명 JWT로 실행한 실제 HTTP 연결 시험에서 +10초, 이전 버전 재전송 409, -10초를 확인했다. 운영 계정과 운영 DB로 시험한 결과는 아니다.

세트 복제와 휴식 문서 정합화까지 2026-09-13 23:42 KST에 **Maven verify 167개, 실패/오류/건너뜀 0**, 패키징 통과. 최초 실행의 신규 배열 비교 테스트 2건은 서버 응답에 정상 배열이 있는데 테스트 비교 과정에서 null로 변환되는 문제였다. 동일 JSON의 최소 재현으로 확인한 뒤 순서와 길이를 검사하는 배열 matcher로 고쳤다. 관련 27개 재검증과 최종 전체 167개가 통과했으며 서버 복제 로직을 우회하지 않았다.

세트 인접 삽입·완료/휴식 번호 보존·정지/누적 시간·원본 루틴·메모 보존, 보관 종목의 기존 스냅샷 복제 후 독립 수정/삭제, 300세트 한도, 같은 버전 동시 요청 200/409 및 재조회, 실제 서명 토큰의 소유권 격리를 확인했다. 인증 시험은 MockMvc와 실제 PostgreSQL/로컬 JWKS를 사용하며 실제 카카오·구글 계정 시험과 구분한다. Basic/Bearer OpenAPI 모두 복제 endpoint와 필수 version, 완료 요청의 선택/nullable restSeconds를 확인했다.

docs/error-contract.md의 오래된 restSeconds 필수 설명을 수정했다. 세트 완료의 생략/null은 자동 적용이며, 개인 휴식 설정 PERSONAL의 필수 초 입력과 구분한다. 휴식 조절/세트 복제는 응답 유실 또는409 후 GET으로 확인하고 최신 version으로 자동 재적용하지 않는다. 같은 규칙을 exercise-rest.md와 workout-api.md에도 반영했다.

Java 21 / Spring Boot 4.1.1 / PostgreSQL 17, Flyway V23까지, 앱 테이블 14개다. [ERD](database-erd.md), [폴더 구조](backend-structure.md), [휴식 조절 계약](api-contracts/rest-adjustment.md)을 참고한다.

## 다음 작업과 남은 외부 연결

1. 휴식 조절 PR #60 커밋 4a5220f의 CI 34762648796은 success다. 세트 복제 PR #61/4a119c49도 CI 34763531710 success이며 서버 재시작·DB 중단/복원·컨테이너·실행 유지 검증과 PM 인계까지 완료했다.
2. PM 배정 BE-02는 codex/social-login-contract의 문서 단위다. [실제 로그인 공백·추천안](authentication-gap-analysis.md), [미확정 계약 v0.3](api-contracts/social-login-draft.md), [FE 대응·PM 수치 결정표](api-contracts/social-login-fe-alignment.md)를 작성했다. 기존 토큰 검증을 재개발하지 않았고 실제 등록·새 비밀·운영 설정·계정 연결·DB 시험을 수행하지 않았다. 발급 주체·로그인 진입·계정 유지/이전·refresh 유실·logout 철회 범위는 추천안/대안과 결정 주체를 PM에게 인계한다.
3. 카카오·구글 로그인은 제공자 등록과 실제 로그인·갱신·로그아웃·계정 연결이 남았다. 현재 JWT 검증 기반을 실제 로그인 완료로 표현하지 않는다.
4. 운영 PostgreSQL은 아직 생성·연결·배포하지 않았다. 현재는 개발 구성과 전용 테스트 DB로 개발 가능하며 사용자 수동 테이블 생성은 필요 없다. [운영 준비](production-readiness.md)를 참고한다.
5. 운동 미디어의 유료 구매·전체 교체는 사용자 최종 확인 이후다. 프론트 화면이나 자산 교체는 이 작업 범위에 포함하지 않는다.

PM 공통 coordination/README.md 및 backend-history-handoff.md v1을 읽었다. 공통 status.md는 PM만 수정한다. FE의 기존 미커밋 변경 의도·검증 이력·임시 미리보기 정보를 PM에 전달했으며 이후 FE 파일/브랜치/테스트 서버는 변경하지 않았다.

BE-02의 검증은 문서의 JSON 예제·내부 링크·기존 코드/공식 문서 대조다. 이 문서의 신규 auth 경로는 아직 생성 OpenAPI나 런타임에 존재하지 않으며, 167개 통과는 선행 구현의 결과다. 다음 구현은 PM 검토 후 확정된 계약을 먼저 커밋하고 시작한다. 테스트 DB는 FE에 인계했으므로 추가 로컬 실DB시험 전 PM과 사용 일정을 조율한다.

2026-09-14 PM 후속 요청으로 [적대적 시나리오 검토 v0.2](authentication-adversarial-review.md)를 추가했다. 앱 시작 PKCE와 handoff 불변 결합, 동시 callback/교환, URL/로그 유출, WEB의 별도 쿠키·CSRF·저장 계약, 기존 owner key 전환, refresh 재사용 시 활성 grant 폐기, logout access 잔존/오프라인 문구를 보완했다. 이는 문서 검토이며 실제 공격 재현이나 신규 인증 구현을 완료한 것이 아니다. v0.1→v0.2에서 JSON 예제는 유지하고 WEB 예약값과 보안 경계를 명확히 했다.

같은 날 FE PR #41/e89d7b6의 A01~A07을 지정 코드 c7503cf과 대조해 초안 v0.3에 반영했다. OIDC 키69자와 기존 FE100자 조건을 확인하고 ID 원문 보존·별도 인증 transport·/me 재검증·facade 재생성·epoch 직렬 보안 저장을 명시했다. D01~D07은 수치가 있는 PM 추천안이며 구현 확정이 아니다. 첫 연결 구현 순서와 등록값 없이 할 모의 시험/실제 등록 의존 시험을 분리했다. 이번에도 런타임·마이그레이션·DB 행·FE 코드는 변경하지 않았다.

2026-10-29 FE 인계: 실제 FE createAuthApi/AuthCoordinator가8096에서 Google alice/bob·Kakao alice, PKCE교환/me/refresh/logout·기존access401을 검증했다는 PM 보고를 받았다(PR47). Node browser/PKCE/memorySecureStore 어댑터이며 실기기 보안저장 시험은 아니다. 첫 assertion 중단 실행의 fake grant1개가 남았을 수 있으나 안전한 식별 근거가 없어 추정 삭제하지 않았으며 전용fixture의 만료 대상으로 둔다. preview 유지/업데이트는 PM과 조율한다.
