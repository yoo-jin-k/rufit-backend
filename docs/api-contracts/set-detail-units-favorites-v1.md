# 세트 종류·자각도, 무게 단위, 종목 즐겨찾기 v1

상태: 구현·테스트 완료, 개발 서버 미배포 (2026-11-15)
도입: V38~V40. 모두 로그인한 계정 기준이며, 다른 계정의 값은 읽거나 바꿀 수 없습니다.

## 1. 세트 종류·운동 자각도 (기존 세트 요청·응답에 필드 추가)

운동 세트 입력(`SetInput`)과 응답의 `sets[]`에 두 필드가 생깁니다. 둘 다 생략할 수 있어 이전 앱도 그대로 동작합니다.

| 필드 | 형식 | 생략 시 | 제약 |
| --- | --- | --- | --- |
| `setType` | `NORMAL` · `WARMUP` · `DROP` · `FAILURE` | `NORMAL` | 그 밖의 값은 400 |
| `rpe` | 정수 | 미입력(`null`) | 5~10, 범위 밖은 400 |

- 적용 경로: 세트 전체 저장, 세트 수정·추가, 초안 저장, 오프라인 가져오기, 지난 기록 조회, 루틴.
- 세트 복제는 종류를 이어받고 자각도는 비웁니다.
- 오프라인 가져오기 재전송 판별(지문)은 일반 세트·자각도 없음이면 이전과 같은 값을 냅니다.

## 2. 무게 표시 단위

`GET /api/v1/preferences/units` → `200 {"version":0,"weightUnit":"KG"}` (저장한 적 없으면 version 0, KG)

`PUT /api/v1/preferences/units` `{"version":0,"weightUnit":"LB"}` → `200 {"version":1,"weightUnit":"LB"}`

- `weightUnit`: `KG` 또는 `LB`. 그 밖의 값은 400.
- `version`은 마지막으로 읽은 값을 보냅니다. 다른 기기가 먼저 바꿨으면 409이며, 다시 조회 후 저장합니다.
- 세트 무게는 언제나 kg로 저장합니다. 앱이 lb로 입력받으면 kg로 바꿔 보냅니다(소수 둘째 자리).

## 3. 종목 즐겨찾기

| 요청 | 응답 |
| --- | --- |
| `GET /api/v1/exercises/favorites` | `200 {"favorites":[{"exerciseId":"…","createdAt":"…"}]}` 최근 추가 순 |
| `PUT /api/v1/exercises/{id}/favorite` | `200 {"exerciseId":"…","createdAt":"…"}` 이미 있으면 기존 값 그대로 |
| `DELETE /api/v1/exercises/{id}/favorite` | `204` 없어도 204 |

- 볼 수 없는 종목(없는 id, 다른 사람의 개인 종목)은 404.
- 계정당 500개까지, 넘으면 400.
