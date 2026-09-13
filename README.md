# 루핏 백엔드

Java 21 · Spring Boot 4.1.1 · PostgreSQL · Maven Wrapper.
프론트는 별도 `yoo-jin-k/rufit-frontend`에서 관리합니다.

운동 시작부터 루틴·이력·세트/휴식·저장 복구까지의 API와 PostgreSQL 검증 기반을 구현했습니다. 현재 코드는 기능 브랜치의 검토 대상이며 운영 배포 상태가 아닙니다. [구현 현황과 남은 연결](docs/api-plan.md), [프론트 연결·검증 순서](docs/integration-guide.md)를 먼저 참고합니다.

[백엔드 폴더와 처리 흐름](docs/backend-structure.md), [현재 DB 구조와 ERD](docs/database-erd.md)에서 실제 14개 테이블, 외래 키와 복사 출처의 차이, 개발·시험·운영 DB 상태를 확인할 수 있습니다.

## 실행

1. Java 21을 선택합니다.
2. 환경변수 `DB_PASSWORD`를 설정하고 `docker compose up -d`로 PostgreSQL을 실행합니다.
3. `./mvnw spring-boot:run`을 실행합니다.
4. `http://127.0.0.1:8080/actuator/health`에서 상태를 확인합니다.

DB_URL(기본 localhost:5433/rufit), DB_USERNAME(기본 rufit), DB_PASSWORD로 연결을 지정합니다.
운영 인증 연동 전에는 기본 프로필에서 업무 API를 차단합니다. 로컬 기능 테스트는 `local` 프로필과 Spring Security의 개발 인증을 사용하며 CSRF 보호를 유지합니다. OIDC 토큰 검증 기반은 구현했으며 실제 로그인 제공자 등록·모바일 로그인은 후속 작업입니다.

## 검증

루프백 호스트의 전용 PostgreSQL DB `rufit_test`와 계정 `rufit_test`를 준비하고 DB_URL, DB_USERNAME, DB_PASSWORD를 설정한 뒤 `./mvnw -B verify`를 실행합니다. 다른 DB·계정은 테스트 시작 전에 거절합니다. [실행 보호 조건](docs/backup-restore.md)을 참고합니다.
CI는 PostgreSQL 17에서 Flyway 마이그레이션, 애플리케이션 로딩, 보안·API 테스트를 실행합니다.
H2로 PostgreSQL 호환성 검증을 대체하지 않습니다.

## 개발

백엔드 전담으로 `developer` 기준의 기능 브랜치에서 작업하고 `developer` 대상 PR을 만듭니다. PM 기준에 따라 새 작업은 `codex/<기능>-<작업>`으로 나누고 기존 진행 `feature/*`는 보존합니다. 기존 `develop` 대상 PR은 이전 작업 이력입니다. main/developer 직접 수정·병합 금지. 기능 구현 전 [API 계약](docs/api-contracts/README.md)을 먼저 커밋하고 DB·API·검증을 함께 구현합니다. 미병합 PR 의존성과 누적 범위는 PR 본문에 명시합니다.

[최근 개발 현황과 다음 작업](docs/development-status.md)

## API 명세와 인증 구현 상태

OIDC 토큰 검증 기반은 구현했으며 실제 로그인 제공자 등록·모바일 로그인·운영 배포는 아직입니다. [인증 설정](docs/authentication.md)을 참고합니다.

`RUFIT_API_DOCS_ENABLED=true`로 명시적으로 켜면 인증 후 `/v3/api-docs`에서 OpenAPI 3.0 JSON을 조회할 수 있습니다. 기본값은 비활성입니다. 명세도 업무 API와 동일한 인증 보호를 받습니다. Swagger UI는 포함하지 않습니다.

`./mvnw verify`는 응답 스키마 이름 충돌, 필드 존재 여부와 null 허용, 인증 방식을 검사합니다. `target/openapi.json`은 개발용 Basic 명세이고 `target/openapi-oidc.json`은 Bearer 명세입니다. 두 파일은 CI의 `backend-api-contract` 아티팩트에서 내려받을 수 있습니다. OIDC 명세에는 개발용 CSRF 경로가 없습니다.

응답의 `required`는 JSON에 필드가 항상 존재한다는 뜻이며 `nullable`은 그 값이 null일 수 있다는 뜻입니다. 예를 들어 운동 종료 전에도 `finishedAt` 필드는 존재하고 값은 null입니다. 요청의 선택 항목(`SetInput.exerciseId`, `Draft.note`)은 필수로 바꾸지 않습니다. 비공개 프로필의 `visibility`는 `PRIVATE`만 반환합니다.

- [운동·루틴 API와 입력 예시](docs/workout-api.md)
- [구현 범위와 남은 작업](docs/api-plan.md)
- [오류 계약](docs/error-contract.md)
- [종목 카탈로그](docs/exercise-catalog.md) · [루틴 검색](docs/routine-search.md) · [기본 휴식 설정](docs/rest-preferences.md)
- [비공개 프로필](docs/profile-api.md) · [서버 생존·준비 상태](docs/health-probes.md)
- [DB 중단·백업 복원 검증](docs/backup-restore.md) · [컨테이너 실행](docs/container-runtime.md)
- 실제 서버 재시작 검증: 테스트 DB 환경변수 설정 후 `python3 scripts/smoke-restart.py`

[오프라인 운동 가져오기](docs/offline-workout-import.md): 원래 시각·세트·완료 상태·메모를 계정에 원자적으로 저장하고 같은 요청의 재시도·삭제 후 복원을 제어합니다. 실제 앱의 가져오기 선택 화면은 후속 작업입니다.

[운동시간 정지·진행 규칙](docs/workout-pause.md)

[종목별 권장·개인 휴식시간](docs/exercise-rest.md)

[운영 DB·보안 준비와 남은 일](docs/production-readiness.md)

[API 계약 우선 작업](docs/api-contracts/README.md), [휴식시간 조절 요청·응답](docs/api-contracts/rest-adjustment.md)

[입력한 세트의 인접 복제 요청·응답](docs/api-contracts/set-duplication.md)
