# 운동기록 API v1

기능 브랜치 구현이며 아직 운영 배포되지 않았다. 기본 프로필에서는 업무 API를 차단하며 test/local 프로필에서 인증한 요청으로 검증한다. 실제 회원·로그인은 후속 작업이다.

| 요청 | 응답 | 목적 |
|---|---|---|
| POST /api/v1/workouts | 201 View | 현재 인증 사용자 운동 시작 |
| GET /api/v1/workouts/{id} | 200 View | 본인 운동 조회 |
| PUT /api/v1/workouts/{id}/exercises | 200 View | 운동 세트 목록 전체 교체 |
| POST /api/v1/workouts/{id}/finish | 200 View | 운동 종료 |

View: `id`, `startedAt`, `finishedAt`(진행 중 null), `version`, `sets`.
세트는 현재 입력 순서와 운동 이름으로 구성한다. 개별 세트 식별자·완료·휴식은 후속 sets-rest 브랜치에서 확장한다.

목록 교체 요청 예:

```json
{
  "version": 0,
  "sets": [
    { "exerciseName": "스쿼트", "weight": 60.5, "reps": 10 },
    { "exerciseName": "스쿼트", "weight": 60.5, "reps": 8 }
  ]
}
```

종료 요청은 `{"version":현재버전}`. 변경 응답의 version을 다음 요청에 사용한다.

- 운동 이름 1~80자, 세트 1~300개, 무게 0~1500kg(소수 2자리), 횟수 1~1000.
- 유효하지 않은 요청 400; 미인증 401(local/test); 타인/없는 기록 404; 버전 충돌·종료 후 수정·중복 활성 운동 409.
- 사용자별 진행 중 운동 최대 1개, DB 부분 유니크 인덱스로 동시 시작도 방어한다.
- JPA @Version과 요청 version을 함께 사용한다.
- CSRF 보호가 활성화되어 있다. 브라우저·세션 인증을 쓰는 클라이언트는 CSRF 토큰을 처리해야 한다. 테스트는 Spring Security csrf()로 검증한다.
- start 요청 재시도는 현재 409이며 기존 운동을 돌려주는 멱등 응답은 아직 없다.
- 오류 상세 정보 및 운영 인증 계약은 후속 검증이 필요하다.

## 로컬 API 확인

`SPRING_PROFILES_ACTIVE=local`, `SPRING_SECURITY_USER_NAME`, `SPRING_SECURITY_USER_PASSWORD`를 지정한다. 기본 주소는 127.0.0.1:8080이다. 로컬 인증은 개발 전용이다.

1. HTTP Basic 인증으로 GET /api/v1/csrf를 호출하고 세션 쿠키를 보관한다.
2. 응답의 headerName 헤더에 token을 넣고 같은 세션 쿠키 및 Basic 인증으로 POST/PUT을 호출한다.
3. 개발 인증·CSRF 획득도 통합 테스트에서 실제 Basic 자격 증명으로 확인한다.

## 세트 완료와 휴식

- PUT /api/v1/workouts/{id}/sets/{setIndex}/completion: `version`, `completed`, `restSeconds`(0~3600).
- setIndex는 현재 sets 배열의 0부터 시작하는 위치다. 목록 전체 교체 시 완료 상태와 휴식은 초기화되며 반드시 최신 version을 사용한다.
- POST /api/v1/workouts/{id}/rest/skip: `version`.
- 응답에 completedSetIndexes와 restEndsAt(UTC 절대시각)을 포함한다. 앱은 max(0, restEndsAt - 현재시각)으로 남은 시간을 표시한다. 서버는 초마다 카운터를 저장하지 않는다.
- 같은 세트를 이미 완료한 상태에서 다시 완료해도 휴식 종료시각을 늘리지 않는다. 해당 세트 완료 취소와 운동 종료는 연결된 휴식을 해제한다.
- 운동 종료는 완료한 세트가 최소 1개 필요하다. 미완료 세트가 있으면 409 `INCOMPLETE_SETS_CONFIRMATION_REQUIRED`를 반환하며 기록은 변경하지 않는다.
- 사용자가 미완료 세트 제외에 동의한 뒤 `POST /{id}/finish`에 `{ "version": 최신버전, "discardIncomplete": true }`를 보내면 완료 세트만 순서대로 보존하고 종료한다. 생략 시 false이며 모두 완료했다면 확인 없이 종료한다.
- 근거: [개발 실행판 POL-06](https://www.notion.so/3d9b67c8d01381c8b8bec2c5d9696c64).

## 저장 재시도·복구

- POST /workouts에 UUID 형식의 Idempotency-Key 헤더를 보낼 수 있다. 같은 사용자가 같은 키로 재시도하면 기존 운동의 현재 상태를 반환하며 새 운동을 만들지 않는다. 키가 없으면 새 ID를 발급한다.
- 같은 키의 동시 최초 요청 중 하나는 DB 충돌로 409가 될 수 있다. 동일 키 재시도로 기존 결과를 조회할 수 있다.
- GET /workouts/active는 본인의 진행 중 운동, 세트 입력, 완료 상태, 휴식 종료시각을 반환한다. 없으면 204.
- 수정은 최신 version을 요구하며 DB 낙관적 잠금으로 동시에 저장한 변경의 조용한 덮어쓰기를 막는다. 409 응답 시 최신 상태를 조회해 사용자가 변경을 다시 적용해야 한다.
- 서버 DB 복구와 앱 오프라인 저장은 별개다. 모바일 로컬 저장·자동 재전송은 아직 연결하지 않았다.

## 개별 세트 편집

- `POST /api/v1/workouts/{id}/sets`: `{ "version": 최신버전, "set": { "exerciseName": "스쿼트", "weight": 40, "reps": 10 } }`로 마지막에 세트 추가.
- `PUT /api/v1/workouts/{id}/sets/{setIndex}`: 같은 요청 형식으로 해당 세트만 수정. 기존 완료 여부와 휴식 종료시각을 유지한다.
- `POST /api/v1/workouts/{id}/sets/{setIndex}/remove`: `{ "version": 최신버전 }`으로 해당 세트 삭제. 남은 완료 표시와 휴식 대상 위치를 보정하며 휴식 대상 자체를 삭제한 경우 타이머를 해제한다.
- 모두 200 View 반환. 다른 사용자 404, 오래된 version 또는 종료한 운동 409, 잘못된 위치·입력·300개 초과 400.
- 세트 위치는 응답의 sets 배열 기준이다. 목록 변경 후에는 반드시 새 View와 version을 사용한다. 오래된 위치를 가진 수정은 version 검사에서 거절된다.
- 전체 세트를 삭제해도 진행 중인 빈 초안은 유지되며 종료는 할 수 없다. 전체 목록 교체 API는 기존대로 모든 완료 표시와 휴식 시간을 초기화하므로 일상적인 입력 변경에는 개별 편집 API를 사용한다.

## 종목별 이전 기록

`GET /api/v1/workouts/exercise-history?exerciseName=스쿼트&page=0&size=10`

- 본인이 완료한 운동 중 이름이 정확히 일치하는 종목의 세트만 최신 완료시각순으로 반환한다. 앞뒤 공백은 제거하며 종목 ID 카탈로그 도입 전까지 이름을 기준으로 조회한다.
- 응답: `exerciseName`, `items[{workoutId,finishedAt,sets}]`, `page`, `size`, `totalElements`, `hasNext`.
- 같은 운동 세션에 해당 종목이 여러 세트 있어도 세션은 한 번만 반환하고 세트의 원래 순서를 보존한다.
- 진행 중인 운동과 다른 사람의 기록은 제외한다. 기록이 없으면 200과 빈 items. page 0~10000, size 1~50.

## 진행 중 운동 취소

`POST /api/v1/workouts/{id}/cancel`에 `{ "version": 최신버전, "confirmed": true }`를 보낸다. 화면에서 취소 확인을 받은 뒤 호출해야 하며 confirmed 누락·false는 400이다.

- 취소 후 View의 `cancelledAt`에 시각이 기록된다. 완료시각은 null이고 휴식은 해제한다. 입력 내용은 취소된 초안에 보존되며 완료 기록/종목별 이전 기록/진행 중 복구에서 제외한다.
- 새로운 운동을 시작할 수 있다. 취소된 운동은 다시 수정하거나 완료할 수 없으며 409를 반환한다.
- 최초 생성에 사용한 Idempotency-Key를 재전송하면 취소된 기존 View가 반환된다. 네트워크 재시도로 취소한 운동이 되살아나지 않는다.
- 완료한 운동의 취소와 취소된 초안 재개는 지원하지 않는다. 기록의 실제 삭제 정책은 별도 기능이다.

## 월별 달력 집계

`GET /api/v1/workouts/calendar?month=2026-09&zone=Asia/Seoul`

- month는 1900-01~2100-12, zone은 Java에서 지원하는 시간대 ID이며 기본 Asia/Seoul이다.
- 본인의 완료한 운동을 **시작 시각의 현지 날짜**로 분류한다. 월 경계는 해당 시간대의 월 시작 이상, 다음 달 시작 미만이다.
- days는 운동이 없는 날도 포함한 월 전체 날짜순 배열. 각 날짜는 `date`, `workouts`, `completedSets`, `volumeKgReps`(완료 세트의 kg × 횟수 합), `durationSeconds`를 가진다.
- 최상위에는 같은 합계 필드와 `workoutDays`(운동한 날짜 수)를 반환한다. 맨몸 운동은 세트 수에는 포함하고 외부 중량이 0이면 운동량 합은 0이다.
- 운동 시간은 시작~완료 경과시간이며 휴식을 포함한다. 진행 중·취소된 운동·다른 사용자 기록은 집계하지 않는다.
