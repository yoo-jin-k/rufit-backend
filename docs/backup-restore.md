# 테스트 데이터베이스 백업·복원 검증

`scripts/verify-backup-restore.py`는 전용 `rufit_test` 데이터베이스를 읽어 별도의 임시 데이터베이스에 복원합니다. 운영 데이터베이스를 덮어쓰는 도구가 아닙니다. 원본 DB와 역할은 `rufit_test`로 고정되어 있습니다.

1. 백엔드 전체 테스트와 `scripts/smoke-restart.py`를 실행해 테스트 운동 기록을 준비합니다.
2. `DB_PASSWORD`에 해당 테스트 DB의 비밀번호를 설정합니다. `RUFIT_TEST_CONTAINER`는 PostgreSQL 컨테이너 이름 또는 ID이며 기본값은 `rufit-postgres-verify`입니다.
3. `python3 scripts/verify-backup-restore.py`를 실행합니다.

스크립트는 원본에서 읽기 전용 스냅샷을 내보내고 같은 스냅샷으로 모든 public 테이블의 행 수·내용 지문과 압축 백업을 생성합니다. 별도의 `rufit_restore_test_<임의 ID>` DB에 복원한 뒤 테이블별 결과가 같은지 비교합니다. 복원에는 데이터와 스키마·제약 조건이 포함되며, 소유권·권한·클러스터 역할 복구는 이 검사 범위가 아닙니다.

복원에 성공한 뒤 임시 DB와 백업 파일을 정리하며 결과는 `target/backup-restore-result.json`에 테이블별 행 수와 검증 시각으로 남깁니다. 실제 행 내용이나 비밀번호는 결과 파일에 기록하지 않습니다. 테스트 DB에 운동 기록이 없으면 검증을 거부합니다.

GitHub 검증은 실제 HTTP 재시작 복구 다음에 이 절차를 실행하고 `backend-data-recovery` 결과 파일을 보관합니다. 운영 서버의 주기적 백업, 보관 기간, 외부 저장소, 재해 복구 시간은 배포 환경을 결정한 뒤 별도로 설정해야 합니다.

## 테스트 DB 실행 조건

서버 테스트는 테스트 클래스패스에만 등록한 초기화 검사로, 연결·마이그레이션 전에 루프백 호스트(`localhost`, `127.0.0.1`, `::1`)의 `rufit_test` DB와 같은 이름의 DB 계정인지 확인합니다. URL 쿼리/사용자정보, 다른 DB·계정, JNDI 같은 간접 연결은 거절합니다. 별도로 설정한 Flyway/Hikari 주소·계정도 검사합니다. 이 검사는 런타임 JAR에 포함되지 않습니다.

JVM 재시작 검증과 프론트 실제 연결 검증도 같은 DB 규칙을 적용합니다. 테스트 자식 프로세스에는 다른 작업의 `SPRING_*` 및 JVM 옵션 환경변수를 넘기지 않고, 검증한 DB 주소·계정을 명시합니다. 이 동작은 사용자의 전역 환경변수를 수정하지 않습니다. 보호 장치는 설정 실수를 줄이기 위한 것이며, `rufit_test`에는 테스트 데이터만 넣어야 합니다.

DB 연결 전에 잘못된 주소가 거절되는 Java 테스트와 `python3 scripts/test_database_guard.py`를 CI에서 실행합니다. 초기화 구조는 [Spring Boot EnvironmentPostProcessor](https://docs.spring.io/spring-boot/4.0/api/java/org/springframework/boot/EnvironmentPostProcessor.html)를 사용합니다.

## 일시적인 DB 중단 후 서버 복구

`python3 scripts/smoke-database-reconnect.py`는 `RUFIT_TEST_CONTAINER`(기본 `rufit-postgres-verify`)의 DB·계정이 `rufit_test`인지, 공개 포트가 DB_URL과 일치하는지 확인한 뒤에만 해당 테스트 컨테이너를 잠시 중지합니다. 같은 테스트 DB를 사용하는 다른 검사와 동시에 실행하지 않습니다. 기존 DB나 볼륨을 삭제하거나 교체하지 않습니다.

검사 순서: 기록 저장 → PostgreSQL 중단 → 읽기·저장 요청 6회의 503 STORAGE_UNAVAILABLE 확인 → PostgreSQL 시작 → 같은 JVM에서 기존 버전·내용 복구 → 실패한 초안의 같은 작업 ID 재시도 → 중복 적용 없이 완료 이력 1개 확인. 실패해도 finally에서 테스트 DB를 다시 시작합니다. 확인 결과는 `target/database-reconnect-result.json`에 기록합니다.

실행 스크립트의 연결 대기 시간은 검사를 위해 1초로 줄입니다. 운영의 장애 감지 시간이나 자동 복구 SLA를 측정하는 부하 검사는 아닙니다. 기존 JVM 재시작 검사와 HTTP 실행 도구를 공유하며, 성공한 실행의 임시 로그는 정리합니다. 실패 로그 경로는 진단용으로 남깁니다.
