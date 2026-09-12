# 운동기록 API v1

기능 브랜치에서 구현·검증했으며 아직 운영 배포되지 않았다. 기본 프로필은 업무 API를 차단한다. local/test는 개발용 Basic+CSRF, oidc는 설정한 발급자의 토큰을 검증한다. 실제 로그인 제공자 등록과 모바일 로그인은 후속 작업이다.

| 요청 | 응답 | 목적 |
|---|---|---|
| POST /api/v1/workouts | 201 View | 현재 인증 사용자 운동 시작 |
| GET /api/v1/workouts/{id} | 200 View | 본인 운동 조회 |
| PUT /api/v1/workouts/{id}/exercises | 200 View | 운동 세트 목록 전체 교체 |
| POST /api/v1/workouts/{id}/finish | 200 View | 운동 종료 |

View: `id`, `startedAt`, `finishedAt`, `cancelledAt`, `sourceRoutineId`, `version`, `sets`, `completedSetIndexes`, `restEndsAt`.
미완료·미취소 시각, 자유 운동의 원본 루틴, 비활성 휴식 시각은 null이다. 세트는 현재 입력 순서와 운동 이름으로 구성한다.

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
- local/test의 Basic 인증에는 CSRF 토큰이 필요하다. oidc 프로필은 쿠키 없이 Bearer 헤더를 사용한다. [인증 계약](authentication.md)을 참고한다.
- 생성 재시도는 Idempotency-Key로 기존 결과를 조회한다. 수정 충돌은 최신 version을 다시 읽어 처리한다. 오류 형식은 [오류 계약](error-contract.md)을 참고한다.

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

## 루틴 시작 재시도

`POST /api/v1/routines/{id}/start`도 UUID `Idempotency-Key` 헤더를 받는다. 같은 루틴을 시작하는 하나의 작업에는 같은 키를 유지하고 새 운동을 시작할 때는 새 키를 만든다.

같은 소유자·루틴·키로 재시도하면 이미 생성된 운동의 현재 View를 반환한다. 원본 루틴이 바뀌거나 운동 중 무게를 수정했어도 다시 복사하지 않는다. View의 `sourceRoutineId`로 시작한 루틴을 확인할 수 있다. 자유 운동은 null이다. 자유 운동 또는 다른 루틴 시작에 이미 사용한 키는 409다. 기존 취소·완료 상태도 그대로 반환한다.

## 완료·취소 기록 삭제

`POST /api/v1/workouts/{id}/delete`에 `{ "version": 최신버전, "confirmed": true }`를 보낸다. 성공은 204다. 진행 중 운동은 먼저 취소해야 한다. 확인 누락·false는 400, 다른 사용자 404, 오래된 버전·진행 중 상태는 409다.

운동 본문·세트·완료 표시를 DB에서 삭제하므로 완료 이력·종목별 이력·달력 집계에서도 빠진다. 같은 사용자의 같은 삭제 요청 재시도는 204다. 삭제한 생성 키를 다시 사용하면 본인은 410 RESOURCE_DELETED, 다른 사용자는 409를 받아 과거 요청으로 기록이 되살아나지 않는다.

재생성 방지를 위해 별도 retired_workout_keys에 UUID·내부 소유권 키·삭제시각만 보존한다. 운동 내용이나 무게·횟수는 보존하지 않는다. 이 최소 메타데이터의 운영 보존기간 및 회원 탈퇴 시 처리는 계정 삭제 정책에서 확정해야 한다.

## 완료 기록 정정

`PUT /api/v1/workouts/{id}/history`에 `{ "version": 최신버전, "sets": [SetInput, ...] }`를 보낸다. 본인이 완료한 기록만 정정할 수 있고 입력한 1~300개 세트는 모두 완료한 세트로 보존한다. 시작·완료 시각은 유지한다. 결과는 200 View이며 이전 종목 기록과 달력 집계에도 즉시 반영한다.

진행 중·취소한 운동은 409, 다른 사용자 404, 오래된 버전 409, 빈 목록이나 잘못된 수치는 400이다. 운동을 수행 중 편집하는 API와 분리되어 있으며 이 API로 진행 상태를 다시 열지는 않는다.

## 루틴 관리와 완료 이력 목록

- `POST /api/v1/routines`: `{ "title": "하체", "sets": [SetInput, ...] }`, 201 Routine View.
- `GET /api/v1/routines?page=0`: 본인 루틴 배열, 페이지당 20개, page 0~10000.
- `GET /api/v1/routines/{id}`: 본인 Routine View.
- `PUT /api/v1/routines/{id}`: `{ "version": 최신버전, "title": "하체", "sets": [SetInput, ...] }`, 200 Routine View.
- Routine View: `id`, `title`, `version`, `sets`. 루틴 제목 1~80자, 세트 1~300개. 수정은 진행 중인 운동 스냅샷에 영향을 주지 않는다.
- `GET /api/v1/workouts?page=0&size=20`: 본인 완료 이력 최신순. `items`, `page`, `size`, `totalElements`, `hasNext`. page 0~10000, size 1~100. 진행 중·취소·삭제 기록 제외.

## 루틴 저장 재시도와 삭제

루틴 생성도 UUID `Idempotency-Key`를 받는다. 같은 사용자·키로 다시 저장하면 기존 루틴의 현재 View를 반환하고 중복 루틴을 만들지 않는다. 다른 사용자의 키는 409다.

`POST /api/v1/routines/{id}/delete`에 `{ "version": 최신버전, "confirmed": true }`를 보내면 루틴과 구성 세트를 삭제하고 204를 반환한다. 같은 삭제의 재시도도 204다. 루틴으로 이미 시작한 운동은 독립된 스냅샷이므로 삭제하거나 수정하지 않는다. 삭제된 루틴으로 새 운동을 시작하면 404다.

최소 재시도 방지 메타데이터(UUID·내부 소유권 키·삭제시각)만 retired_routine_keys에 남기며 삭제한 생성 키는 다시 사용할 수 없다. 본인의 재사용은 410, 다른 사용자는 409다. 운영 보존·탈퇴 정책은 계정 삭제 정책과 함께 확정해야 한다.

## 세트·완료·휴식의 일괄 저장

`PUT /api/v1/workouts/{id}/draft`는 진행 중 운동의 유효한 세트 입력, 완료 표시, 휴식을 하나의 트랜잭션으로 저장한다.

```json
{
  "operationId": "00000000-0000-4000-8000-000000000099",
  "version": 3,
  "sets": [
    { "set": { "exerciseName": "스쿼트", "weight": 40, "reps": 10 }, "completed": true }
  ],
  "restSetIndex": 0,
  "restEndsAt": "2026-09-13T01:00:30Z"
}
```

- operationId는 한 저장 작업의 UUID다. 네트워크 재시도에서는 같은 ID를 사용한다. 이미 적용한 작업이면 오래된 version이어도 현재 기록을 반환하여 이후 변경을 덮어쓰지 않는다. 응답은 workout(View), appliedVersion(해당 작업 적용 시 버전), replayed로 구성한다. workout.version이 appliedVersion보다 크면 이후 변경이 있으므로 클라이언트는 대기 중 변경을 자동 덮어쓰지 말고 충돌을 해결해야 한다. 다른 내용의 새로운 저장에는 새 operationId를 사용한다.
- 새로운 작업은 최신 version을 요구한다. 빈 초안이나 같은 내용도 버전을 갱신하여 동시 전체 저장의 충돌을 감지한다. 충돌이면 최신 View를 조회한 뒤 사용자 변경을 다시 적용한다.
- sets는 빈 목록~300개이며 각 입력은 기존 수치 조건을 만족해야 한다. 빈 입력 문자열 등 편집 중 값은 기기에 보존하고 유효한 값만 서버로 보낸다.
- 휴식은 완료한 세트의 위치와 UTC 종료시각을 함께 보내거나 둘 다 null/생략한다. 서버 현재시각 기준 최대 3600초 이후까지만 허용하며 이미 지난 휴식은 해제한다. 예시 시각은 설명용으로 실제 호출 시 현재 종료시각을 사용한다.
- View에 restSetIndex를 함께 반환한다. 실패 시 입력·완료·휴식·작업 적용 표시는 모두 롤백한다. 완료·취소한 운동에는 새 초안을 저장할 수 없다.
- 적용 작업 UUID·버전·시각은 운동 삭제 시 함께 제거한다. 오프라인 큐와 화면 연결은 별도이며 이 API만으로 모바일 오프라인 동기화가 완료된 것은 아니다.

루틴 시작 요청을 같은 `Idempotency-Key`로 재시도할 때는 이미 생성된 운동을 먼저 확인합니다. 원본 루틴이 이후 삭제되어도 생성된 운동은 복구할 수 있습니다. 다른 키로 삭제된 루틴을 새로 시작하는 요청은 404이며, 다른 루틴에 사용한 시작 키는 재사용할 수 없습니다.

### 루틴 복사

`POST /api/v1/routines/{id}/copy`에 `{"version":0,"title":"하체 B"}`와 `Idempotency-Key` UUID를 전달합니다. 본인 원본의 확인한 버전에서 종목 ID·이름 스냅샷·무게·횟수를 복사하며 독립된 새 루틴을 201로 반환합니다. 원본 버전이 달라졌으면 409입니다. 읽기는 PostgreSQL 반복 읽기 스냅샷으로 원본 메타데이터와 세트를 함께 읽습니다.

복사본의 `sourceRoutineId`는 원본 ID이며 일반 생성은 null입니다. 원본 수정/삭제는 복사본에 영향을 주지 않습니다. 기존 템플릿의 보관 종목도 스냅샷으로 유지합니다. 같은 키의 재시도는 원본이 삭제되어도 기존 복사본의 현재 상태를 반환합니다. 복사본 자체를 삭제한 뒤 같은 키를 재사용하면 410입니다. 일반 생성과 복사, 다른 원본의 복사는 같은 키를 공유할 수 없습니다.

### 종목 누적 통계

`GET /api/v1/workouts/exercise-statistics?exerciseId={UUID}` 또는 `?exerciseName=스쿼트`로 본인의 종료한 운동만 집계합니다. ID 조회는 이름이 바뀐 기록도 합산하고, 이름 조회는 공백을 정리한 정확한 이름만 합산합니다. 두 선택자는 함께 사용할 수 없습니다.

응답은 운동 횟수(`workouts`), 완료 세트 수, 총 반복 수, 무게×횟수 합계(`volumeKgReps`), 최근 수행 시각과 가장 무거운 완료 세트(`heaviestSet`)입니다. 무게가 같으면 횟수, 종료 시각, 운동 ID 내림차순, 세트 순서 오름차순으로 하나를 정합니다. 추정 1RM이나 추천 무게가 아닙니다. 기록이 없으면 합계는 0, 최근 시각과 최고 무게 세트는 null입니다.

진행 중·취소된 운동과 다른 사용자의 기록은 제외됩니다. 완료 기록을 수정하거나 삭제하면 다음 조회에 반영됩니다. 통계와 최고 무게 세트는 같은 SQL 스냅샷에서 계산합니다.

### 운동 메모

운동 응답의 `note`는 기본 빈 문자열입니다. `PUT /api/v1/workouts/{id}/note`에 `{"version":3,"note":"컨디션과 다음 운동 메모"}`를 보내 진행 중 또는 완료된 본인 운동의 메모를 수정합니다. 최대 2000자이며 줄바꿈을 보존합니다. 빈 문자열은 지우기, null은 잘못된 입력입니다. 취소된 운동은 수정할 수 없습니다.

초안 일괄 저장에도 선택적인 `note`를 보낼 수 있습니다. 세트·완료·휴식·메모가 함께 저장되며 같은 작업 ID의 재시도는 이후 수정한 메모를 덮어쓰지 않습니다. 초안 요청에서 note를 생략하거나 null로 보내면 기존 메모를 유지합니다. 오프라인 메모 변경은 별도 요청보다 일괄 저장 작업에 포함하는 것이 적합합니다.
