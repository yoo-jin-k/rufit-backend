# 백엔드 개발 현황

2026-09-13 기준. 이 문서는 실제 구현·검증과 남은 작업을 구분한다.

## 담당 범위와 브랜치

- rufit-backend만 담당한다. Spring Boot API·인증/인가·PostgreSQL·JPA·비즈니스 규칙·입력 검증·예외처리·테스트·명세를 관리한다.
- 사용자 요청에 따라 developer를 기준으로 feature/*에서 작업하고 developer 대상 PR을 만든다. 원격 developer가 없어 기존 main/develop과 같은 초기 커밋 2e6b193에서 생성했다. main/develop은 수정하지 않았다.
- 이전 develop 대상 기능 PR은 아직 미병합이다. 의존 기능을 포함하는 PR은 누적 범위와 선행 PR을 밝힌다. 사용자 확인 없이 기준 브랜치로 병합하거나 운영에 배포하지 않는다.
- 새 기능은 docs/api-contracts에 요청·응답·오류·재시도 규칙을 먼저 확정해 커밋한 뒤 구현한다. 기능마다 쉬운 한국어 주석을 단다.

## 구현과 검증

| 작업 | 구현 | 확인한 범위 |
| --- | --- | --- |
| 운동시간 정지/진행 | POST /api/v1/workouts/{id}/pause, /resume | 명시적 정지만 시간을 멈춤, 종료 시 정지 시간 제외. PR #58 |
| 종목별 휴식 | GET/PUT /api/v1/exercises/{id}/rest | 개인값(0초 포함) → 종목 추천 → 공통 기본값, 소유권·버전 검사. PR #59 |
| 현재 휴식 조절 | POST /api/v1/workouts/{id}/rest/adjust | ±10초, 만료 후 재시작, 0~3600초 제한, 중복 적용 방지. developer 대상 PR #60 |
| 입력한 세트 복제 | POST /api/v1/workouts/{id}/sets/{setIndex}/duplicate | 계약 확정, 구현 전. [요청·응답](api-contracts/set-duplication.md) |
| 기존 주요 API | 운동·루틴·이력·통계·기록 정정/삭제·오프라인 가져오기 | 사용자 격리, 동시 수정, 응답 유실·재시작·DB 장애/복구 시험 |

휴식 조절 구현까지 전용 PostgreSQL에서 Maven verify 160개 테스트가 통과했다. 이후 DTO 명세와 주석 보완 후 API 계약·휴식 조절 관련 6개 테스트도 통과했다. 서명 JWT로 실행한 실제 HTTP 연결 시험에서 +10초, 이전 버전 재전송 409, -10초를 확인했다. 운영 계정과 운영 DB로 시험한 결과는 아니다.

Java 21 / Spring Boot 4.1.1 / PostgreSQL 17, Flyway V23까지, 앱 테이블 14개다. [ERD](database-erd.md), [폴더 구조](backend-structure.md), [휴식 조절 계약](api-contracts/rest-adjustment.md)을 참고한다.

## 다음 작업과 남은 외부 연결

1. 현재 휴식 조절 PR #60의 CI 결과를 확인한다. DTO 명세 보완 후 OIDC 7개 테스트와 Basic/Bearer 명세의 ±10 enum 일치도 확인했다.
2. 먼저 명세한 세트 복제 API를 구현한다. 원본 값 복사, 인접 삽입, 완료·휴식 대상 이동, 동시 수정 방지를 함께 검증한다.
3. 카카오·구글 로그인은 제공자 등록과 실제 로그인·갱신·로그아웃·계정 연결이 남았다. 현재 JWT 검증 기반을 실제 로그인 완료로 표현하지 않는다.
4. 운영 PostgreSQL은 아직 생성·연결·배포하지 않았다. 현재는 개발 구성과 전용 테스트 DB로 개발 가능하며 사용자 수동 테이블 생성은 필요 없다. [운영 준비](production-readiness.md)를 참고한다.
5. 운동 미디어의 유료 구매·전체 교체는 사용자 최종 확인 이후다. 프론트 화면이나 자산 교체는 이 작업 범위에 포함하지 않는다.
