# 운동 카탈로그 v2 계약

2026-10-29, **계약 선커밋 afa42b3 / 구현·로컬 검증 완료**. PM의110종 후보를 검토하고 기존18+신규92를 채택했다. 계약 커밋 뒤 V26과 검색을 구현했다. 로컬 전체202시험 통과이며 운영/FE 앱 반영 완료는 별도다.

## 원본과 보존

- FE/BE 공유 원본: `src/main/resources/catalog/exercises-v2.json`, catalogVersion=`2026-10-29.v2`, exerciseCount=110. 각 항목은 id/name/bodyPart/equipment/aliases/recommendedRestSeconds다.
- 기존18의 ID·이름·부위·장비·별칭·권장휴식값을 유지한다. 기존 `src/test/resources/catalog/mobile-picker.json`도 과거18개 정체성 표본으로 유지한다. 이미지/미디어 주소는 추가·교체하지 않는다.
- 신규92는 candidateKey로 UUIDv5(namespace URL, 문자열 `urn:rufit:exercise:<candidateKey>`)를 한 번 생성해 원본에 고정했다. FE는 계산하지 말고 확정 id를 그대로 사용한다. 이름을 고쳐도 ID를 재생성하지 않는다. candidateKey 자체는 API ID가 아니다.
- 신규92의 recommendedRestSeconds는 **null**이다. 새 운동 처방을 만들지 않고 기존 개인값→종목값→공통 설정의 휴식 정책을 사용한다. null을 임의 추천 숫자로 바꾸지 않는다.
- 가슴15/등16/하체29/어깨14/팔18/복부13/전신5. 기존7부위·6장비 enum만 사용한다. OTHER는 밴드/짐볼/케틀벨/앱 휠 등이다.
- 반복/중량 기록에 맞는 동작만 포함하며 유산소 시간·거리/정적유지는 제외한다. 별칭으로 충분한 표기 변형은 독립 ID로 늘리지 않았다. 이름·정규화한 별칭의 다른 운동 간 정확 충돌은 없다. 부분 검색으로 여러 운동이 나오는 것은 정상이다.

## API (응답 모양 유지)

| API | 인증·요청 | 성공 |
| --- | --- | --- |
| GET /api/v1/exercises | 기존 인증 필수. query, bodyPart, equipment, page, size, includeArchived | 200 기존 Results |
| GET /api/v1/exercises/{id} | UUID, 기존 인증 필수 | 200 기존 View |

기존 개인 종목 생성/수정/보관 API는 유지한다. 공용 운동은 개인 수정/보관 대상으로 취급하지 않는다. 소유권은 인증 principal에서 정하며 다른 사람의 개인 운동은 검색/조회할 수 없다.

- query 기본빈값, 최대80자, page0~10000, size1~100(기본20). 정렬은 기존 이름/id 오름차순이다.110종 전체 조회에는 페이지를 순회한다.
- 공용 종목의 이름·별칭 검색을 확장한다. NFKC → 소문자 → 공백/밑줄/하이픈 제거 후 부분 일치한다. 예: `사레레`, `케이블 크로스오버`, `ＤＵＭＢＢＥＬＬ＿ＦＬＹ`. query/bodyPart/equipment는 AND다.
- 기존 이름 원문 검색도 유지한다. 개인 종목은 기존 이름 검색/보관·소유권 규칙을 그대로 적용하며 공용 별칭을 다른 개인 종목에 복사하지 않는다.
- `%`, `_`, `!`를 SQL 와일드카드/escape 명령으로 해석하지 않는다. 별칭 정규화에서 제거하는 `_`와 기존 이름 원문 검색을 구분한다. 같은 종목의 여러 별칭이 맞아도 한 행만 반환한다.
- aliases는 배포용 JSON 원본에만 있다. 기존 API View 필드에 추가하지 않으므로 클라이언트 응답 스키마를 바꿀 필요가 없다.

요청 예: `GET /api/v1/exercises?query=사레레&bodyPart=SHOULDERS&equipment=DUMBBELL&page=0&size=20`

```json
{"items":[{"id":"00000000-0000-4000-8000-000000000014","name":"덤벨 사이드 레터럴 레이즈","bodyPart":"SHOULDERS","equipment":"DUMBBELL","custom":false,"archivedAt":null,"version":0,"recommendedRestSeconds":90}],"page":0,"size":20,"totalElements":1,"hasNext":false}
```

형식/범위/enum 오류400, 미인증401, 없는/다른 사람 종목404, 저장소 장애503는 기존 Problem 응답을 유지한다. 조회는 변경을 일으키지 않으며 GET 재시도 가능. 신규 쓰기/동시수정 계약은 없다.

## DB·검증 계획

V1~V25를 수정하지 않고 V26으로 신규92행과 공용 이름·별칭의 검색 색인 테이블을 추가한다. 기존18행/운동·루틴·개인휴식 참조와 저장된 운동명 스냅샷은 변경하지 않는다. 별칭은 종목 ID에 FK 연결하고 UNIQUE(exercise_id,normalized_alias)로 중복을 막는다.

전용 PostgreSQL에서 원본110개와 DB/상세 API 일치, 이름·별칭 검색과 복합필터/페이지 중복 없음, 소유권, 신규 휴식 fallback, V25에 넣은 기존 기록/루틴/휴식이 V26 뒤 동일한지 검사한다. FE가 검증 중인 preview8096/DB 및 rufit-backend-auth 작업공간은 수정·재기동·초기화하지 않는다.

## 검토 근거

PM 후보 검토와 함께 [ACE 짐볼 레그 컬](https://www.acefitness.org/resources/everyone/exercise-library/59/stability-ball-hamstring-curl/), [CrossFit 덤벨 쓰러스터](https://www.crossfit.com/essentials/the-dumbbell-thruster), [ACE 케틀벨 목록](https://www.acefitness.org/resources/everyone/exercise-library/equipment/kettlebells/?page=2)을 확인했다. 명칭·장비·동작 구분을 확인하는 자료이며 운동 처방/사진/상세 설명을 복사하지 않았다. 대표 부위와 한국어명은 탐색을 위한 프로젝트 편집 분류다.

## 구현 검증 결과

- V26: 신규92행, 이름/별칭 검색키240행. 기존18행 갱신 없음.
- 새6개 시험은110원본/DB/상세API 일치, 전체 별칭, 7×6 복합필터, 페이지 중복 없음, 신규 휴식fallback/소유권, 새 종목1kg2회 저장·정식이름 재조회, V25→V26 보존을 포함한다.
- 전용 PostgreSQL17/Java21 Maven verify202개(실패0/오류0/건너뜀0), 패키징 통과. 자동시험5437만 사용했다.
- 마이그레이션 시험은 별도 임시 스키마에 V25를 먼저 적용하고 과거 운동명·루틴명 스냅샷·개인0초휴식·수정버전을 넣은 뒤 V26으로 올렸다. 이전18행과 업무행의 JSON 전체가 같음을 확인하고 해당 임시 스키마만 제거했다.
- GitHub CI는 선행 로그인 PR에서 확인한 계정 결제/지출 한도 문제로 실행 차단 상태다. 로컬 성공과 구분한다. PM 조율 후 preview8096을 실행 코드9964ac0으로 갱신했다. DB/볼륨을 유지하며 V26만 추가했고, 기존18/업무/회원/세션15개 테이블 범위의 행 수·내용 해시가 동일했다. 새 로그인/갱신/폐기 및 실제 HTTP110종2페이지·별칭3개도 통과했다.

추가 복구 검증: 자동시험DB를 일회용 DB로 백업·복원하여 Flyway 이력을 포함한22개 테이블/431행의 내용 해시가 일치했다. 임시 복원 DB는 제거했고 preview에는 접근하지 않았다. `scripts/smoke-exercise-catalog.py`는 인증16KiB와 분리된 업무 응답256KiB 한도로 실제110종을 검사한다. 결과는 `target/catalog-http-result.json`, `target/catalog-preview-upgrade-result.json`, `target/backup-restore-result.json`이며 비밀 원문은 포함하지 않는다.
