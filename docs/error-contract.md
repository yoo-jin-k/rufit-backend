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
