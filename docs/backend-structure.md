# 백엔드 코드 구조

Java 21, Spring Boot 4.1.1, Spring Data JPA, PostgreSQL로 구현한 한 개의 백엔드 애플리케이션이다. 프론트 저장소와 분리하며 서버 내부 코드는 기능별로 묶는다.

```text
src/main/java/com/rufit/
├── RufitBackendApplication.java   애플리케이션 시작점
├── workout/                      운동·세트·휴식·이전 기록·집계
├── routine/                      루틴·복사·루틴에서 운동 시작
├── exercise/                     공용/개인 종목·검색·부위·도구
├── profile/                      비공개 프로필·목표·휴식 설정
├── config/                       인증·보안·요청/응답 설정
└── common/                       공통 오류 응답·요청 추적

src/main/resources/
├── application.properties        DB·JPA·상태 확인 등의 기본 설정
├── application-oidc.properties   외부 발급자 토큰 검증 설정
└── db/migration/                 Flyway V1~V21 SQL

src/test/                        단위·HTTP·실제 PostgreSQL 검사
scripts/                         재시작·DB 장애·백업/복원·컨테이너 검증
```

| 역할 | 운동 기록 예시 | 책임 |
| --- | --- | --- |
| Controller | WorkoutController | 요청 형식 검증, 인증된 사용자 전달, 응답 구성 |
| Service | WorkoutService | 본인 기록 확인, 수정 버전 확인, 처리 흐름과 트랜잭션 |
| Entity | Workout | 종료한 운동의 변경 제한, 세트·휴식·완료 규칙과 상태 |
| Repository | WorkoutRepository | JPA로 운동 조회·저장 |

운동 추가 요청은 Controller → Service → Entity/Repository → PostgreSQL 순서로 처리한다. 기본 저장은 JPA이고 달력·통계 등 일부 조회와 저장 확인 메타데이터는 직접 SQL/JDBC를 사용한다. 모든 기능이 같은 파일 수를 가지는 것은 아니며 필요한 책임에 맞춰 나눈다.

작업은 운동 기록 → 루틴 → 이전 기록 → 세트/휴식 → 저장·복구 순서로 API, 규칙, DB 변경, 검증을 함께 추가했다. 인증된 사용자 소유권, 동시 수정 충돌, 같은 요청의 재시도, 실패 시 원문 보존을 각 기능에서 확인했다. 현재 기능 기준 135개 백엔드 테스트를 실제 PostgreSQL로 검증했다. 문서만 바꾸는 작업에서는 전체 검사를 불필요하게 다시 실행하지 않는다.

기능 코드는 feature/* 브랜치에서 작업하고 develop을 대상으로 검토용 PR을 만든다. main/develop 직접 수정·병합은 하지 않았다. 실제 로그인 제공자 등록과 운영 배포는 아직 완료되지 않았다.

DB 관계와 개발/시험/운영 환경의 구분은 [ERD](database-erd.md), 연동 상태는 [프론트 연결 안내](integration-guide.md)에 정리한다.
