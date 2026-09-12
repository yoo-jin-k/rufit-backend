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

조회 응답은 items/page/size/totalElements/hasNext다. 종목 View는 id/name/bodyPart/equipment/custom/archivedAt/version이며 소유권 키는 노출하지 않는다. 이름 1~80자, page 0~10000, size 1~100(기본20). 검색의 %, _, !는 문자 그대로 처리한다. 보관 종목은 includeArchived=true로 함께 조회할 수 있다.

bodyPart: CHEST, BACK, SHOULDERS, LEGS, ARMS, CORE, FULL_BODY.
equipment: BARBELL, DUMBBELL, MACHINE, CABLE, BODYWEIGHT, OTHER.

다른 사람의 개인 종목 조회/수정은404, 공용 종목 수정·보관도404다. 버전 충돌과 동일한 활성 개인 종목 이름은409다. 보관한 종목과 동일한 이름을 새로 만들었다면 기존 종목 복원이 이름 충돌로 거절될 수 있다. 보관은 데이터 삭제가 아니며 기존 운동 기록을 변경하지 않는다.

같은 생성 키의 재시도는 현재 종목을 반환하며 보관 상태도 유지한다. 다른 사용자의 키는409다. 현재 운동·루틴 세트는 이름 스냅샷을 사용한다. 카탈로그 ID와 기록 연결은 다음 기능에서 확장한다.
