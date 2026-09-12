# 루핏 백엔드

Java 21 · Spring Boot 4.1.1 · PostgreSQL · Maven Wrapper.
프론트는 별도 `yoo-jin-k/rufit-frontend`에서 관리합니다.

## 실행

1. Java 21을 선택합니다.
2. 환경변수 `DB_PASSWORD`를 설정하고 `docker compose up -d`로 PostgreSQL을 실행합니다.
3. `./mvnw spring-boot:run`을 실행합니다.
4. `http://127.0.0.1:8080/actuator/health`에서 상태를 확인합니다.

DB_URL(기본 localhost:5433/rufit), DB_USERNAME(기본 rufit), DB_PASSWORD로 연결을 지정합니다.
운영 인증 연동 전에는 기본 프로필에서 업무 API를 차단합니다. 로컬 기능 테스트는 `local` 프로필과 Spring Security의 개발 인증을 사용하며 CSRF 보호를 유지합니다. 실제 배포용 인증은 아직 구현하지 않았습니다.

## 검증

전용 PostgreSQL 테스트 DB의 DB_URL, DB_USERNAME, DB_PASSWORD를 설정하고 `./mvnw -B verify`.
CI는 PostgreSQL 17에서 Flyway 마이그레이션, 애플리케이션 로딩, 보안·API 테스트를 실행합니다.
H2로 PostgreSQL 호환성 검증을 대체하지 않습니다.

## 개발

`feature/project-setup` → `develop` PR. 이후 `feature/workout-record`, `feature/routine`, `feature/history`, `feature/sets-rest`, `feature/save-recovery` 순서로 진행합니다.
main 직접 수정 금지. 기능마다 DB·API·검증을 함께 구현합니다. 미병합 PR 의존성은 PR 본문에 명시합니다.
