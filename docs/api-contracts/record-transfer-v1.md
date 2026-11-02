# 기기 기록 이전·측정 동기화 v1

상태: 구현 및 전용 PostgreSQL 자동 검증 완료 (2026-11-02). 기존 소셜 세션 Bearer 인증과 오류 형식을 따릅니다. 소유자는 principal에서 결정하며 요청에 owner를 받지 않습니다.

## 운동 가져오기 확장

`POST /api/v1/workouts/import`, `Idempotency-Key: UUID`. 기존 요청의 시작/종료 시각·완료 세트·confirmed:true·정지시간 계약은 유지합니다.

선택 필드: `recordDate: YYYY-MM-DD|null`, `entryMode: "manual"|null`. 두 필드는 함께 보내며 manual은 완료 기록만 허용합니다. 날짜는 1900-01-01~Asia/Seoul 오늘이며 수동 기록은 startedAt=finishedAt, 정지시간 0입니다. 작성 시각과 실제 운동 날짜를 각각 보존합니다. 응답 Workout View에도 두 필드가 추가됩니다. 과거 필드 미전송 요청의 지문과 재시도 호환성은 유지합니다.

응답 200 `{workout, importedVersion, replayed}`. 같은 키/같은 원본은 재처리하지 않으며 다른 내용/소유자는 기존 409 규칙을 따릅니다. 완료 날짜별 조회와 주·월 집계는 recordDate가 있으면 그 날짜를 사용합니다. 시간 미측정 기록은 운동시간에 0초를 더합니다.

## 체중·삼대 측정

- GET `/api/v1/measurements/{kind}?page=0&size=100` : kind는 `weight` 또는 `strength`, page 0~10000, size 1~100. 날짜 내림차순. 200 `{items,page,size,totalElements,hasNext}`.
- PUT `/api/v1/measurements/{kind}/{date}` : 새 기록 version=0, 수정은 조회한 version. 200 최신 레코드. 날짜는 1900-01-01~Asia/Seoul 오늘.
- weight 요청 `{version:0,weight:70.25}`. 0 초과 700kg 이하, 소수 둘째 자리까지.
- strength 요청 `{version:0,bench:80,squat:100,deadlift:120}`. 각 0 초과 1000kg 이하, 소수 둘째 자리까지. 실제 측정 중량이며 추정 1RM이 아닙니다.
- 응답 `{date,version,weight,bench,squat,deadlift}`. 해당 종류가 아닌 값은 null. 버전은 최초 1부터 증가합니다.
- 같은 값 재전송은 현재값을 반환하여 응답 유실 뒤 중복 증가를 방지합니다. 값이 다른 오래된 version은 409 STATE_CONFLICT. 클라이언트는 재조회 후 사용자 선택 없이 덮어쓰지 않습니다.
- 다른 계정의 같은 날짜는 완전히 분리됩니다. 빈 목록은 items:[]이며 삭제/공개 API는 제공하지 않습니다.

## 오류

기존 ProblemDetail JSON `{type,title,status,detail,code}` 및 validation errors를 사용합니다. 400 INVALID_INPUT/VALIDATION_FAILED/INVALID_JSON, 401 인증 필요, 409 STATE_CONFLICT, 503 STORAGE_UNAVAILABLE. DB 오류의 내부 메시지나 비밀값은 응답하지 않습니다.

## 프론트 동작

설정의 계정 기록 화면에서 사용자가 명시적으로 업로드/가져오기를 실행합니다. 기기 자료의 서버·계정 연결을 최초 저장하고 다른 계정으로 같은 자료를 전송하지 않습니다. 원본은 삭제하지 않습니다. 운동 업로드는 네트워크 전 UUID와 요청본문을 저장해 재시도 때 같은 값을 사용합니다. 측정값이 같은 날짜에 서로 다르면 서버/기기 값을 표시하고 선택 후 적용합니다. 기기 값이 비교 이후 바뀌면 적용을 중단합니다.

사진·프로필·친구 기록은 이 계약에 포함하지 않습니다. 현재 서버에 미디어 저장소/API가 없어 별도 계약과 저장소 구성이 필요합니다.

## 검증과 적용 범위

Java 21 / PostgreSQL 전용 테스트 환경에서 `mvn verify` 전체 205개 테스트가 통과했습니다. 날짜별 측정값의 소유권 분리·재전송·버전 충돌·입력 검증, 수동 운동의 날짜별 조회·월 집계·중복 요청 검증을 포함합니다. V25에서 최신 스키마로 이전할 때 기존 열의 값이 유지되고 추가 선택 필드가 null인 것도 확인했습니다.

실제 사용자 서버와 DB는 재시작·이전하지 않았습니다. 운영 적용에는 V27과 새 애플리케이션 배포가 함께 필요합니다. 실제 소셜 로그인 및 네이티브 기기 왕복 전송은 별도 검증 대상입니다.
