# 모바일 종목 선택 목록

V20에서 공용 종목 8개를 추가해 모바일의 사진 목록 18개와 ID·한국어 이름·부위·도구를 맞춘다. 기존 V10 종목과 기록은 수정하지 않는다.

| UUID 마지막 번호 | 종목 | 부위 | 도구 |
| --- | --- | --- | --- |
| 11 | 맨몸 스쿼트 | LEGS | BODYWEIGHT |
| 12 | 덤벨 스쿼트 | LEGS | DUMBBELL |
| 13 | 프론트 스쿼트 | LEGS | BARBELL |
| 14 | 덤벨 사이드 레터럴 레이즈 | SHOULDERS | DUMBBELL |
| 15 | 덤벨 숄더 프레스 | SHOULDERS | DUMBBELL |
| 16 | 덤벨 업라이트 로우 | SHOULDERS | DUMBBELL |
| 17 | 덤벨 벤치 프레스 | CHEST | DUMBBELL |
| 18 | 크런치 | CORE | BODYWEIGHT |

UUID 형식은 `00000000-0000-4000-8000-0000000000NN`이며 모든 종목은 owner_name=NULL인 공용 항목이다. 사진과 이용 조건은 프론트 저장소에 보관하며 서버가 외부 이미지 주소를 호출하지 않는다. 이 변경으로 API 응답 형태는 바뀌지 않는다.

`MobileExercisePickerTests`는 실제 PostgreSQL에서 모바일 목록 18개의 정체성, 복합 필터, 새 종목으로 1kg 2회 세트 두 개 저장·개별 수정·삭제·재조회와 소유자 분리를 검증한다. `src/test/resources/catalog/mobile-picker.json`은 프론트 목록과 공유하는 계약 표본이다. 프론트의 실제 HTTP 통합 시험도 프론트 catalog.ts의 전 항목을 서버 응답과 비교하므로 표본만 바뀌는 드리프트를 확인한다.

프론트 화면은 현재 로컬 저장 모드이며 로그인/실제 서버 저장 연결 완료를 의미하지 않는다. 서버 이름 검색은 기존 공용/소유 종목 검색을 유지하고 프론트의 별칭 검색은 번들 목록에서만 적용한다.
