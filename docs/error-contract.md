# API 오류 계약

오류는 application/problem+json과 status, title, detail, code를 사용한다.

| code | HTTP | 클라이언트 동작 |
|---|---|---|
| VALIDATION_FAILED | 400 | errors의 field/code를 해당 입력 필드에 표시 |
| INVALID_JSON / INVALID_INPUT | 400 | 요청 형식 확인 |
| AUTHENTICATION_REQUIRED | 401 | 로그인/인증 갱신 |
| ACCESS_DENIED | 403 | 권한 또는 CSRF 상태 확인 |
| NOT_FOUND | 404 | 없는 항목 또는 접근할 수 없는 소유자 기록으로 처리 |
| STATE_CONFLICT | 409 | 최신 상태를 조회하고 기능별 재시도 계약에 따라 처리. 최신 버전으로 자동 재적용하지 않음 |
| METHOD_NOT_ALLOWED | 405 | API 호출 방식 수정 |
| UNSUPPORTED_MEDIA_TYPE | 415 | Content-Type 수정 |

검증 오류는 field와 제약 code만 포함하며 입력한 원본 값·자격증명·SQL·스택트레이스를 반환하지 않는다.

기록·루틴·프로필 및 종목의 여러 테이블/페이지 조회는 반복 읽기 트랜잭션으로 같은 시점의 버전과 내용을 반환합니다. PostgreSQL이 동시 수정으로 트랜잭션을 중단한 경우도 409 `STATE_CONFLICT`로 반환합니다. 데이터베이스 예외와 SQL은 응답에 포함하지 않습니다. 서버가 임의로 버전을 바꿔 다시 쓰지 않으며, 클라이언트가 최신 상태를 확인하고 처리해야 합니다.

상세 조회와 초안 재시도에서 메타데이터를 읽은 직후 실제 다른 트랜잭션이 세트를 수정하는 회귀 테스트를 두었습니다. 수정 전에는 예전 버전과 새 무게가 섞였고, 수정 후에는 해당 버전의 무게가 유지됩니다. 이 읽기는 동시 작성자의 커밋을 막는 행 잠금에 의존하지 않습니다.

## 오류 확인 번호

서버가 처리하는 요청마다 새 UUID를 생성해 `X-Request-Id` 응답 헤더로 돌려줍니다. 클라이언트가 보낸 같은 이름의 헤더 값은 사용하지 않습니다. 인증 거절·입력 오류에도 번호가 있으며 허용된 웹 origin에는 해당 응답 헤더를 읽도록 CORS에서 노출합니다. 요청을 다시 보내면 새 번호가 생기므로 저장 재시도 키와는 별개입니다.

요청 처리 중 로그의 `requestId` 문맥에 같은 번호를 넣고 종료 시 정리합니다. 오류·비동기 디스패치에서는 요청에 저장된 번호를 유지합니다. 상태 로그는 HTTP 방식과 상태 코드만 남기며 토큰·요청 본문·쿼리·사용자 식별자를 추가하지 않습니다. 헬스 체크는 상태 로그를 생략합니다. 서버에 도달하지 않은 연결 실패나 앞단 프록시의 응답에는 번호가 없을 수 있습니다.

앱의 `ApiError.requestId`가 있으면 테스트 실패를 재현할 때 그 번호로 서버 로그를 찾을 수 있습니다. 이것만으로 외부 모니터링 서비스나 분산 추적이 구성된 것은 아닙니다.

동작 근거: [Spring OncePerRequestFilter](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html), [Spring Boot MDC 로그 설정](https://docs.spring.io/spring-boot/reference/features/logging.html).

DB 연결을 열지 못하거나 저장소 연결이 끊긴 경우는 `503 STORAGE_UNAVAILABLE`의 공통 오류로 반환합니다. DB 주소·SQL·연결 설정을 응답에 넣지 않습니다. 쓰기 요청의 성공 여부는 클라이언트에서 불명확한 상태로 유지합니다. 연결 복구 후 생성/초안 저장처럼 재시도 키를 지원하는 API는 같은 작업 ID로 처리 결과를 확인하고, 휴식 조절/세트 복제처럼 version만 쓰는 API는 먼저 GET으로 최신 상태를 조회합니다. 이 오류를 저장 완료나 인증 실패로 처리하지 않습니다.

## 409와 응답 유실의 기능별 처리

| 요청 | 처리 규칙 |
| --- | --- |
| 휴식 ±10초 조절 | GET으로 version/restEndsAt을 다시 확인. 이전 version의 요청을 최신 version으로 자동 변경해 재적용하지 않음. 이미 적용된 +10초가 다시 더해질 수 있음 |
| 세트 복제 | GET으로 세트 배열/version을 다시 확인. 번호가 다른 원본을 가리킬 수 있으므로 최신 version 자동 재전송 금지 |
| 원자적 초안 저장 | 같은 operationId는 이미 처리된 결과 확인에 사용. 충돌 시 원본 입력을 보존하고 최신 상태와 대조한 뒤 명시적으로 처리 |
| 생성 요청 | API가 지원하는 동일 Idempotency-Key와 동일 생성 조건으로 처리 결과 확인. 기존 운동 진행 내용을 초기화하지 않음 |

409가 곧 같은 변경의 실패를 확정하는 것은 아닙니다. 앞선 성공 응답을 잃고 같은 version으로 재전송한 경우도 409일 수 있습니다. 휴식 조절과 세트 복제는 새 사용자의 조작으로 확정된 경우에만 최신 상태를 기준으로 별도 요청합니다. [휴식 조절 계약](api-contracts/rest-adjustment.md)과 [세트 복제 계약](api-contracts/set-duplication.md)을 참고합니다.

## 입력 타입을 보존하는 JSON 정책

반복 횟수·버전·휴식 초처럼 정수인 필드에 소수를 보내면 400 INVALID_JSON으로 거절합니다. 예를 들어 `reps: 1.9`를 1회로 잘라 저장하지 않습니다. 숫자 필드의 문자열(`"10.5"`), 불리언 필드의 문자열(`"true"`), 문자열 필드의 숫자도 자동 변환하지 않습니다. 열거 값은 정해진 이름으로 보내며 숫자 순번을 받지 않습니다. 중복 JSON 필드 이름도 거절합니다.

세트 완료 요청의 `restSeconds`는 선택 항목입니다. 생략 또는 null이면 개인 지정 → 종목 추천 → 계정 기본값 순서로 자동 적용합니다. 정수 0은 이번 완료의 휴식을 끄고, 1~3600은 이번 요청에만 지정한 초를 적용합니다. 음수·3600 초과는 400, 숫자 문자열·소수는 400 INVALID_JSON입니다. version과 completed는 여전히 필수이며 null을 허용하지 않습니다. 잘못된 요청은 기존 버전·세트·완료 표시를 변경하지 않습니다.

이는 종목 설정 변경 API의 mode와 구분합니다. PUT /api/v1/exercises/{id}/rest에서 PERSONAL은 restSeconds 0~3600을 필수로 받고, RECOMMENDED는 생략/null만 받아 개인 지정을 해제합니다. [종목별 휴식 계약](exercise-rest.md)과 실제 Completion DTO, ExerciseRestTests/StrictRecordInputTests가 같은 규칙을 검증합니다.

[Spring Boot JsonMapper 설정](https://docs.spring.io/spring-boot/how-to/spring-mvc.html)의 사용자 정의 설정을 사용하며 현재 Jackson 3의 타입별 기능을 적용합니다.
## 인증 서비스의 일시 장애

OIDC 서명 키 조회 등 인증 서비스 오류는 HTTP 503, `AUTHENTICATION_UNAVAILABLE`로 반환합니다. 잘못된 토큰의 401과 구분하고 WWW-Authenticate 헤더를 보내지 않습니다. 응답에는 고정된 안내와 서버가 생성한 요청 확인 번호를 사용하며 외부 키 주소나 장애 응답 본문을 넣지 않습니다. 앱은 로그인 정보나 보관 중인 초안을 지우지 말고 연결 복구 후 최신 상태를 확인합니다. 키 서버가 복구되면 같은 서버 프로세스에서 다시 검증할 수 있습니다.
