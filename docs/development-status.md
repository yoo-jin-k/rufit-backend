# 백엔드 개발 현황

2026-09-13 기준. 이 문서는 실제 구현·검증과 남은 작업을 구분한다.

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
| 입력한 세트 복제 | POST /api/v1/workouts/{id}/sets/{setIndex}/duplicate | 계약 97ec685 먼저 공개 후 구현·로컬 검증 완료. [요청·응답](api-contracts/set-duplication.md) |
| 기존 주요 API | 운동·루틴·이력·통계·기록 정정/삭제·오프라인 가져오기 | 사용자 격리, 동시 수정, 응답 유실·재시작·DB 장애/복구 시험 |

휴식 조절 구현까지 전용 PostgreSQL에서 Maven verify 160개 테스트가 통과했다. 이후 DTO 명세와 주석 보완 후 API 계약·휴식 조절 관련 6개 테스트도 통과했다. 서명 JWT로 실행한 실제 HTTP 연결 시험에서 +10초, 이전 버전 재전송 409, -10초를 확인했다. 운영 계정과 운영 DB로 시험한 결과는 아니다.

세트 복제와 휴식 문서 정합화까지 2026-09-13 23:42 KST에 **Maven verify 167개, 실패/오류/건너뜀 0**, 패키징 통과. 최초 실행의 신규 배열 비교 테스트 2건은 서버 응답에 정상 배열이 있는데 테스트 비교 과정에서 null로 변환되는 문제였다. 동일 JSON의 최소 재현으로 확인한 뒤 순서와 길이를 검사하는 배열 matcher로 고쳤다. 관련 27개 재검증과 최종 전체 167개가 통과했으며 서버 복제 로직을 우회하지 않았다.

세트 인접 삽입·완료/휴식 번호 보존·정지/누적 시간·원본 루틴·메모 보존, 보관 종목의 기존 스냅샷 복제 후 독립 수정/삭제, 300세트 한도, 같은 버전 동시 요청 200/409 및 재조회, 실제 서명 토큰의 소유권 격리를 확인했다. 인증 시험은 MockMvc와 실제 PostgreSQL/로컬 JWKS를 사용하며 실제 카카오·구글 계정 시험과 구분한다. Basic/Bearer OpenAPI 모두 복제 endpoint와 필수 version, 완료 요청의 선택/nullable restSeconds를 확인했다.

docs/error-contract.md의 오래된 restSeconds 필수 설명을 수정했다. 세트 완료의 생략/null은 자동 적용이며, 개인 휴식 설정 PERSONAL의 필수 초 입력과 구분한다. 휴식 조절/세트 복제는 응답 유실 또는409 후 GET으로 확인하고 최신 version으로 자동 재적용하지 않는다. 같은 규칙을 exercise-rest.md와 workout-api.md에도 반영했다.

Java 21 / Spring Boot 4.1.1 / PostgreSQL 17, Flyway V23까지, 앱 테이블 14개다. [ERD](database-erd.md), [폴더 구조](backend-structure.md), [휴식 조절 계약](api-contracts/rest-adjustment.md)을 참고한다.

## 다음 작업과 남은 외부 연결

1. 휴식 조절 PR #60 커밋 4a5220f의 CI 34762648796은 success다. 세트 복제 구현을 developer 대상 PR로 올리고 CI와 PM 인계를 마무리한다.
2. 다음 신규 범위는 PM 기능 카드와 계약에 따라 진행한다. 완료된 정지/재개·종목별 휴식을 중복 구현하지 않는다. 현재 작업의 차단 및 추가 사용자 결정은 없다.
3. 카카오·구글 로그인은 제공자 등록과 실제 로그인·갱신·로그아웃·계정 연결이 남았다. 현재 JWT 검증 기반을 실제 로그인 완료로 표현하지 않는다.
4. 운영 PostgreSQL은 아직 생성·연결·배포하지 않았다. 현재는 개발 구성과 전용 테스트 DB로 개발 가능하며 사용자 수동 테이블 생성은 필요 없다. [운영 준비](production-readiness.md)를 참고한다.
5. 운동 미디어의 유료 구매·전체 교체는 사용자 최종 확인 이후다. 프론트 화면이나 자산 교체는 이 작업 범위에 포함하지 않는다.

PM 공통 coordination/README.md 및 backend-history-handoff.md v1을 읽었다. 공통 status.md는 PM만 수정한다. FE의 기존 미커밋 변경 의도·검증 이력·임시 미리보기 정보를 PM에 전달했으며 이후 FE 파일/브랜치/테스트 서버는 변경하지 않았다.
