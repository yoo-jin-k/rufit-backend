# 운동기록 API v1

기능 브랜치 구현이며 아직 운영 배포되지 않았다. 기본 프로필에서는 업무 API를 차단하며 test/local 프로필에서 인증한 요청으로 검증한다. 실제 회원·로그인은 후속 작업이다.

| 요청 | 응답 | 목적 |
|---|---|---|
| POST /api/v1/workouts | 201 View | 현재 인증 사용자 운동 시작 |
| GET /api/v1/workouts/{id} | 200 View | 본인 운동 조회 |
| PUT /api/v1/workouts/{id}/exercises | 200 View | 운동 세트 목록 전체 교체 |
| POST /api/v1/workouts/{id}/finish | 200 View | 운동 종료 |

View: `id`, `startedAt`, `finishedAt`(진행 중 null), `version`, `sets`.
세트는 현재 입력 순서와 운동 이름으로 구성한다. 개별 세트 식별자·완료·휴식은 후속 sets-rest 브랜치에서 확장한다.

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
- CSRF 보호가 활성화되어 있다. 브라우저·세션 인증을 쓰는 클라이언트는 CSRF 토큰을 처리해야 한다. 테스트는 Spring Security csrf()로 검증한다.
- start 요청 재시도는 현재 409이며 기존 운동을 돌려주는 멱등 응답은 아직 없다.
- 오류 상세 정보 및 운영 인증 계약은 후속 검증이 필요하다.

## 로컬 API 확인

`SPRING_PROFILES_ACTIVE=local`, `SPRING_SECURITY_USER_NAME`, `SPRING_SECURITY_USER_PASSWORD`를 지정한다. 기본 주소는 127.0.0.1:8080이다. 로컬 인증은 개발 전용이다.

1. HTTP Basic 인증으로 GET /api/v1/csrf를 호출하고 세션 쿠키를 보관한다.
2. 응답의 headerName 헤더에 token을 넣고 같은 세션 쿠키 및 Basic 인증으로 POST/PUT을 호출한다.
3. 개발 인증·CSRF 획득도 통합 테스트에서 실제 Basic 자격 증명으로 확인한다.
