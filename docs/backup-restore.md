# 테스트 데이터베이스 백업·복원 검증

`scripts/verify-backup-restore.py`는 전용 `rufit_test` 데이터베이스를 읽어 별도의 임시 데이터베이스에 복원합니다. 운영 데이터베이스를 덮어쓰는 도구가 아닙니다. 원본 DB와 역할은 `rufit_test`로 고정되어 있습니다.

1. 백엔드 전체 테스트와 `scripts/smoke-restart.py`를 실행해 테스트 운동 기록을 준비합니다.
2. `DB_PASSWORD`에 해당 테스트 DB의 비밀번호를 설정합니다. `RUFIT_TEST_CONTAINER`는 PostgreSQL 컨테이너 이름 또는 ID이며 기본값은 `rufit-postgres-verify`입니다.
3. `python3 scripts/verify-backup-restore.py`를 실행합니다.

스크립트는 원본에서 읽기 전용 스냅샷을 내보내고 같은 스냅샷으로 모든 public 테이블의 행 수·내용 지문과 압축 백업을 생성합니다. 별도의 `rufit_restore_test_<임의 ID>` DB에 복원한 뒤 테이블별 결과가 같은지 비교합니다. 복원에는 데이터와 스키마·제약 조건이 포함되며, 소유권·권한·클러스터 역할 복구는 이 검사 범위가 아닙니다.

복원에 성공한 뒤 임시 DB와 백업 파일을 정리하며 결과는 `target/backup-restore-result.json`에 테이블별 행 수와 검증 시각으로 남깁니다. 실제 행 내용이나 비밀번호는 결과 파일에 기록하지 않습니다. 테스트 DB에 운동 기록이 없으면 검증을 거부합니다.

GitHub 검증은 실제 HTTP 재시작 복구 다음에 이 절차를 실행하고 `backend-data-recovery` 결과 파일을 보관합니다. 운영 서버의 주기적 백업, 보관 기간, 외부 저장소, 재해 복구 시간은 배포 환경을 결정한 뒤 별도로 설정해야 합니다.
