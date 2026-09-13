# 입력한 세트 복제 API 계약

상태: **계약 확정, 구현 전**. feature/set-duplication에서 구현한다. 선행 PR #60이 포함된 누적 기능이며 PR 대상은 developer다.

## 목적과 엔드포인트

스쿼트 1kg·2회를 입력한 세트의 + 버튼은 원본 바로 뒤에 같은 값의 미완료 세트 1개를 추가한다. 원본 세트의 완료 여부와 다른 세트의 휴식은 유지한다.

| 항목 | 계약 |
| --- | --- |
| HTTP | POST /api/v1/workouts/{id}/sets/{setIndex}/duplicate |
| 인증 | Bearer 액세스 토큰. local/test만 Basic + CSRF |
| id | 인증된 사용자가 소유한 운동 UUID |
| setIndex | 현재 서버 응답의 sets 배열 번호, 0부터 시작 |
| 요청 | application/json, 아래 version만 필요 |
| 성공 | 200 application/json, 갱신된 전체 Workout View |

```json
{"version":12}
```

- version은 필수 정수이며 0 이상이어야 한다. null, 음수, 숫자 문자열, 소수는 400이다.
- 다른 기기에서 순서나 값을 바꿨다면 409를 반환한다. 예전 번호가 가리키는 다른 세트를 복제하지 않는다.
- 입력 값이 아직 서버에 저장되지 않았다면 먼저 세트 수정 또는 draft 저장을 완료하고 그 응답의 version으로 복제한다. 클라이언트는 원본 값을 다시 전송할 필요가 없다.

## 성공 응답 예시

원본은 index 0의 스쿼트 1kg·2회(완료), index 1의 덤벨 컬 5kg·10회(완료, 현재 휴식 대상)다. index 0을 복제하면 새 세트는 index 1, 덤벨 컬은 index 2가 된다.

```json
{
  "id":"11111111-1111-4111-8111-111111111111",
  "startedAt":"2026-09-13T12:30:00Z",
  "finishedAt":null,
  "cancelledAt":null,
  "sourceRoutineId":null,
  "version":13,
  "sets":[
    {"exerciseName":"스쿼트","weight":1,"reps":2,"exerciseId":null},
    {"exerciseName":"스쿼트","weight":1,"reps":2,"exerciseId":null},
    {"exerciseName":"덤벨 컬","weight":5,"reps":10,"exerciseId":null}
  ],
  "completedSetIndexes":[0,2],
  "restEndsAt":"2026-09-13T13:00:10Z",
  "restSetIndex":2,
  "note":"",
  "pausedAt":null,
  "pausedDurationMs":0
}
```

- 운동 이름·무게·횟수·exerciseId를 저장된 원본에서 그대로 복사한다. 원본이 이미 기록에 포함된 보관 종목이어도 스냅샷을 유지한다. 다른 사용자의 운동을 원본으로 사용할 수 없다.
- 새 세트는 항상 미완료다. 복제 자체가 휴식을 시작하거나 기존 휴식을 다시 시작하지 않는다.
- 삽입 위치 뒤의 완료 번호와 휴식 번호만 1씩 이동한다. 기존 휴식 deadline은 그대로다. 원본이 휴식 대상이면 원본 번호도 그대로다.
- 정지 중 운동에서도 복제할 수 있다. 시작/종료/정지 시각·누적 정지 시간·메모·출처 루틴은 유지한다.
- 운동이 300세트이면 추가하지 않고 400을 반환한다. 유효하지 않은 번호도 400이다. 완료/취소된 운동은 409다.
- DB 마이그레이션은 없다. 기존 세트 배열과 완료/휴식 번호를 같은 트랜잭션에서 저장하고 운동 version을 증가시킨다.

## 오류와 재시도

| HTTP | code | 처리 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED / INVALID_INPUT / INVALID_JSON | 요청 값·UUID·번호·세트 한도를 확인 |
| 401 | AUTHENTICATION_REQUIRED | 인증 필요 |
| 403 | ACCESS_DENIED | 개발 CSRF 또는 접근 정책 거절 |
| 404 | NOT_FOUND | 없거나 다른 계정의 운동, 존재 여부를 구분하지 않음 |
| 409 | STATE_CONFLICT | 최신 운동을 다시 읽고 사용자가 선택한 원본을 확인 |
| 503 | STORAGE_UNAVAILABLE / AUTHENTICATION_UNAVAILABLE | 편집 내용을 보존하고 서비스 회복 후 서버 상태 확인 |

오류는 공통 application/problem+json(type/title/status/detail/instance/code, 검증 오류의 errors)을 반환한다. X-Request-Id는 문의·로그 추적용이며 중복 요청 키가 아니다.

응답을 잃었을 때는 GET /api/v1/workouts/{id}로 최신 상태부터 확인한다. 같은 version을 재전송하면 이미 반영된 복제를 다시 수행하지 않고 409다. 최신 version으로 자동 재전송하면 새 복제가 될 수 있으므로 금지한다. 동시에 두 번 누른 요청은 같은 version 기준 최대 한 번만 성공한다.

## - 버튼과 검증 계획

- 삭제는 기존 POST /api/v1/workouts/{id}/sets/{setIndex}/remove에 {"version":현재버전}을 보낸다. 서버 응답으로 세트·완료·휴식 상태를 함께 갱신한다.
- 복제와 삭제 모두 입력한 화면의 version을 사용한다. 응답을 기다리는 동안 같은 세트 변경 버튼을 중복 전송하지 않는다.
- 테스트: 중간/마지막 삽입, 완료·미완료 원본, 휴식 대상 앞/뒤 삽입, 정지 유지, 보관 종목 스냅샷, 300세트 한도, 소유권, 인증/CSRF, 잘못된 입력, 동시/오래된 요청, 트랜잭션 저장 후 재조회, 복제 후 삭제 복원, OpenAPI Basic/Bearer 계약.
