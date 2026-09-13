# 현재 휴식시간 조절 API 계약

상태: 구현 완료, 백엔드160개 실DB 테스트 통과. 이후 기능은 이 형태의 계약을 구현 전에 먼저 작성한다.

## 엔드포인트와 인증

`POST /api/v1/workouts/{id}/rest/adjust`

- Content-Type: application/json
- 운영 연결: Authorization: Bearer 검증된 액세스 토큰. 요청 owner 필드는 받지 않는다.
- 개발/test 프로필: Basic + CSRF, 운영 로그인으로 사용하지 않는다.
- Path id: 운동 UUID.

## 요청

```json
{"version":12,"seconds":10}
```

| 필드 | 형식 | Validation |
| --- | --- | --- |
| version | 정수, 필수 | 0 이상, 서버의 현재 운동 버전과 일치 |
| seconds | 정수, 필수 | -10 또는10만 허용. null/소수/숫자문자열 거절 |

## 성공 응답

200, 공통 Workout View. 현재 DTO를 그대로 반환하며 변경 없는 속성도 포함한다.

```json
{
  "id":"11111111-1111-4111-8111-111111111111",
  "startedAt":"2026-09-13T12:30:00Z",
  "finishedAt":null,
  "cancelledAt":null,
  "sourceRoutineId":null,
  "version":13,
  "sets":[{"exerciseName":"스쿼트","weight":1,"reps":2,"exerciseId":"00000000-0000-4000-8000-000000000001"}],
  "completedSetIndexes":[0],
  "restEndsAt":"2026-09-13T13:00:10Z",
  "restSetIndex":0,
  "note":"",
  "pausedAt":null,
  "pausedDurationMs":0
}
```

startedAt/finishedAt/cancelledAt/restEndsAt/pausedAt은 ISO8601 UTC 문자열(해당 nullable 필드만 null 허용). 세트 번호는0부터 시작한다. 실제 시각은 서버에서 정한다. 응답 version을 다음 변경 요청에 사용한다.

## 동작·오류·재시도

- 남은 휴식에10초를 더하거나 뺀 뒤 현재부터0~3600초로 제한한다. 만료 후+10은 현재부터10초다.
- 완료한 세트/휴식 대상/운동 시작·정지·누적정지시간은 유지한다. 조절할 휴식이 없으면 새 대상을 만들지 않는다.
- 400: INVALID_INPUT / INVALID_JSON / VALIDATION_FAILED. 필수값·형식·범위 오류.
- 401: AUTHENTICATION_REQUIRED. 인증되지 않음.
- 403: ACCESS_DENIED. 개발 CSRF 또는 접근정책 거부.
- 404: NOT_FOUND. 없거나 다른 소유자의 운동.
- 409: STATE_CONFLICT. 이전 버전, 동시에 변경됨, 이미 닫힌 운동, 휴식 없음.
- 503: STORAGE_UNAVAILABLE 또는 AUTHENTICATION_UNAVAILABLE. 의존 서비스 일시 장애.
- 오류는 application/problem+json의 type/title/status/detail/instance/code를 사용한다. 검증 오류에는 errors 필드가 추가될 수 있다. X-Request-Id를 추적에 사용하며 토큰/입력원문을 로그로 남기지 않는다.
- 자동으로 같은 조절을 재전송하지 않는다. 응답을 잃으면 GET /api/v1/workouts/{id}로 version/restEndsAt을 먼저 확인한다. 이전 버전으로 재전송해도 두 번 적용되지 않는다. 사용자가 새로 조절하려면 최신 버전으로 별도 요청한다.

생성 OpenAPI: CI backend-api-contract의 openapi-oidc.json. 담당 코드: WorkoutController.RestAdjustment → WorkoutService.adjustRest → Workout.adjustRest.
