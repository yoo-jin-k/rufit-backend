# 기능별 API 구현 순서

현재는 초기구조 단계다. 아래는 구현 계약이며 아직 동작하는 API 목록이 아니다.

| 브랜치 | API | 핵심 검증 |
|---|---|---|
| feature/workout-record | POST /api/v1/workouts, GET /{id}, PUT /{id}/exercises, POST /{id}/finish | 본인 데이터만 접근, 입력 검증, 트랜잭션, 완료 후 수정 차단 |
| feature/routine | POST/GET/PUT /api/v1/routines, POST /{id}/start | 루틴 소유권, 복사된 운동과 원본 분리 |
| feature/history | GET /api/v1/workouts?status=COMPLETED | 페이지 제한, 정렬, 완료 기록만 조회 |
| feature/sets-rest | PUT /api/v1/workouts/{id}/sets/{setId} | 완료/취소, 휴식 종료시각, 중복 요청 |
| feature/save-recovery | GET /api/v1/workouts/active, 버전 기반 PUT | 낙관적 락, 오래된 변경 거부, 재시도, DB 복구 |

- 모든 시간은 서버 UTC Instant를 기준으로 응답한다.
- 소유자는 인증된 사용자에서 얻고 요청 body의 ownerId를 신뢰하지 않는다.
- 운동 세트: 무게 0~1500kg, 소수점 2자리; 횟수 1~1000. 맨몸 운동은 0kg 허용.
- 오류는 상태코드와 읽을 수 있는 메시지를 포함한다. 다른 사용자 기록은 404로 처리한다.
- 운동 생성 재시도에는 클라이언트 UUID/idempotency key를 고려한다.
- 기본 프로필은 업무 API를 차단한다. local/test 개발 인증은 운영 로그인 구현 완료와 구분한다.
- 각 PR에 실제 테스트 개수·PostgreSQL 검증 여부·미구현 영역을 기록한다.
