# 운동 종목 카탈로그

인증한 사용자는 공용 종목과 본인의 개인 종목을 조회한다. 공용 종목의 이름·부위·장비는 초기 분류이며 운동 처방이나 추천 순위가 아니다.

| 요청 | 동작 |
|---|---|
| GET /api/v1/exercises | query 부분검색, bodyPart/equipment 필터, page/size 페이지 조회 |
| GET /api/v1/exercises/{id} | 공용 또는 본인 개인 종목 상세 |
| POST /api/v1/exercises | name/bodyPart/equipment로 개인 종목 생성, UUID Idempotency-Key 선택 |
| PUT /api/v1/exercises/{id} | version/name/bodyPart/equipment로 본인 종목 수정 |
| POST /api/v1/exercises/{id}/archive | version으로 본인 종목을 검색 기본 목록에서 보관 |
| POST /api/v1/exercises/{id}/restore | version으로 보관한 본인 종목 복원 |

조회 응답은 items/page/size/totalElements/hasNext다. 종목 View는 id/name/bodyPart/equipment/custom/archivedAt/version/recommendedRestSeconds이며 소유권 키는 노출하지 않는다. 이름 1~80자, page 0~10000, size 1~100(기본20). 기존 이름 검색의 %, _, !는 문자 그대로 처리한다. 공용 이름/별칭에는 NFKC·소문자·공백/밑줄/하이픈 제거 검색을 함께 적용한다. 보관 종목은 includeArchived=true로 함께 조회할 수 있다.

bodyPart: CHEST, BACK, SHOULDERS, LEGS, ARMS, CORE, FULL_BODY.
equipment: BARBELL, DUMBBELL, MACHINE, CABLE, BODYWEIGHT, OTHER.

다른 사람의 개인 종목 조회/수정은404, 공용 종목 수정·보관도404다. 버전 충돌과 동일한 활성 개인 종목 이름은409다. 보관한 종목과 동일한 이름을 새로 만들었다면 기존 종목 복원이 이름 충돌로 거절될 수 있다. 보관은 데이터 삭제가 아니며 기존 운동 기록을 변경하지 않는다.

같은 생성 키의 재시도는 현재 종목을 반환하며 보관 상태도 유지한다. 다른 사용자의 키는409다. 운동·루틴 세트는 선택적 카탈로그 ID와 이름 스냅샷을 함께 사용한다.

공용 종목이 데이터 관리 과정에서 보관되어도 상세·종목별 이전 기록·통계·이전 세트 조회는 유지한다. 기본 검색에서는 제외하고 includeArchived=true일 때 표시한다. 일반 사용자는 공용 종목을 수정·보관·복원할 수 없다. 보관 상태는 신규 선택을 제한하며 과거 기록 조회 권한을 제거하지 않는다.

## 운동·루틴 세트와 연결

SetInput에 선택 필드 `exerciseId`를 추가했다. ID와 이름을 함께 보내면 신규 선택의 이름은 서버 카탈로그에서 확인한 값으로 저장한다. 다른 사용자의 개인 종목은 사용할 수 없다. ID가 없는 요청은 기존 자유 입력 방식으로 동작하며 기존 자유 입력 기록을 이름만 보고 임의로 카탈로그에 연결하지 않는다.

동일 ID가 이미 포함된 본인 운동·루틴을 수정할 때는 그 기록의 이름 스냅샷을 유지한다. 카탈로그 이름 변경은 과거 기록을 덮어쓰지 않는다. 보관 종목은 신규 선택할 수 없지만 기존 기록 정정과 기존 루틴에서 운동 시작은 가능하다. 연결된 세트를 유지하려면 수정 요청에도 exerciseId를 보낸다.

`GET /api/v1/workouts/exercise-history?exerciseId={id}`로 이름이 바뀌어도 같은 종목의 이전 기록을 조회한다. exerciseName과 exerciseId 중 하나만 전달한다. 응답의 exerciseName은 현재 카탈로그 이름이고 각 세트의 exerciseName은 기록 당시 이름이다. 다른 사람의 개인 종목 ID 조회는404다. ID 없이 작성한 과거 자유 입력은 ID 이력에 자동 합치지 않는다.

V26에서 공용110종(기존18+신규92)과 검색키240개를 제공한다. [확정 계약·검증](api-contracts/exercise-catalog-v2.md), 공유 원본 `src/main/resources/catalog/exercises-v2.json`을 따른다.110전체는 size100 이하의 페이지를 순회한다. 신규 권장휴식은null이며 기존fallback을 유지한다. aliases는 기존 API View에 추가하지 않는다.
