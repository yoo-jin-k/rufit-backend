# API 오류 계약

오류는 application/problem+json과 status, title, detail, code를 사용한다.

| code | HTTP | 클라이언트 동작 |
|---|---|---|
| VALIDATION_FAILED | 400 | errors의 field/code를 해당 입력 필드에 표시 |
| INVALID_JSON / INVALID_INPUT | 400 | 요청 형식 확인 |
| AUTHENTICATION_REQUIRED | 401 | 로그인/인증 갱신 |
| ACCESS_DENIED | 403 | 권한 또는 CSRF 상태 확인 |
| NOT_FOUND | 404 | 없는 항목 또는 접근할 수 없는 소유자 기록으로 처리 |
| STATE_CONFLICT | 409 | 최신 상태를 조회하고 변경 재적용 |
| METHOD_NOT_ALLOWED | 405 | API 호출 방식 수정 |
| UNSUPPORTED_MEDIA_TYPE | 415 | Content-Type 수정 |

검증 오류는 field와 제약 code만 포함하며 입력한 원본 값·자격증명·SQL·스택트레이스를 반환하지 않는다.

기록·루틴·프로필 및 종목의 여러 테이블/페이지 조회는 반복 읽기 트랜잭션으로 같은 시점의 버전과 내용을 반환합니다. PostgreSQL이 동시 수정으로 트랜잭션을 중단한 경우도 409 `STATE_CONFLICT`로 반환합니다. 데이터베이스 예외와 SQL은 응답에 포함하지 않습니다. 서버가 임의로 버전을 바꿔 다시 쓰지 않으며, 클라이언트가 최신 상태를 확인하고 처리해야 합니다.

상세 조회와 초안 재시도에서 메타데이터를 읽은 직후 실제 다른 트랜잭션이 세트를 수정하는 회귀 테스트를 두었습니다. 수정 전에는 예전 버전과 새 무게가 섞였고, 수정 후에는 해당 버전의 무게가 유지됩니다. 이 읽기는 동시 작성자의 커밋을 막는 행 잠금에 의존하지 않습니다.
