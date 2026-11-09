# 자체 세션 갱신·로그아웃

2026-10-29. v1 선확정 계약을 기준으로 PR65의 코드 교환에 이어 구현했다.

| API | 요청 | 결과 |
| --- | --- | --- |
| POST /api/v1/auth/refresh | refreshToken | 200 같은 account.id/sessionId, isNewAccount=false, 회전한 새 토큰 쌍 |
| POST /api/v1/auth/logout | refreshToken | 204 해당 grant 폐기. 알려지지 않은 정상 형식/이미 폐기한 값도204 |

- 세션 행 잠금으로 refresh 회전을 직렬화한다. 과거 토큰 재사용은 grant 폐기를 **커밋한 뒤**401을 반환한다. 예외 때문에 폐기가 롤백되지 않는다.
- 동시에 같은 refresh 두 개가 오면 최대 하나만200이며, 두 번째 재사용은 첫 응답의 새 토큰까지 폐기한다. response cache/grace/자동 재전송은 없다.
- logout은 알려진 과거/현재 refresh 어느 쪽으로도 같은 grant를 폐기한다. 요청 owner/sessionId를 신뢰하지 않으며 다른 기기 세션·운동 데이터는 유지한다.
- access의 sid를 매 업무 인증마다 DB로 조회한다. 폐기 커밋 이후 새 인증은 기존 access도401. DB 오류는503. 이미 인증을 마친 진행 중 업무를 소급 취소하지 않는다.
- refresh 미사용14일/절대30일, access10분. 절대기한에 가까우면 둘 다 절대기한까지만 발급한다. DB 만료에는 JWT의30초 시계 여유를 적용하지 않는다.
- 인증 원장은 JDBC/명시적 트랜잭션으로 처리한다. 회전 저장 실패는 used_at/기한/새 해시를 모두 롤백한다. 기존 운동 도메인의 JPA 구조는 유지한다.
- 분당 정리 작업은 최대5000행씩 만료 verifier 암호문 제거, 하루 지난 attempt 삭제, 한시간 지난 카운터 삭제, 절대기한+30초 지난 grant와 refresh 관계 삭제를 한다. 회원/identity는 삭제하지 않는다.

## 검증 증거

- 격리 PostgreSQL17 + Java21의 Maven verify: **196개, 실패0/오류0/건너뜀0**, 패키징 통과.
- LoginHttpTests19개는 실제 HTTP fake provider 시작→콜백→교환→me와 refresh/logout 경쟁·즉시 폐기·다른 grant 유지·엄격한 만료·제한·저장 실패 롤백·정리를 포함한다.
- 전체 시험에서 발견한 기존 OIDC CORS 헤더 변경을 social 범위로 한정하고, 시험 컨텍스트의 빈 DB 연결 선점을 test 설정으로 제거했다. 운영 DB 풀 정책은 변경하지 않았다.
- 추가 [FE preview](fake-login-integration.md)는 자동 회귀와 별도 DB/볼륨으로 실행한다. 기동·조율된 재기동 후 실제 HTTP smoke 통과. FE 표준 `rufit://auth/callback`에 맞춘 최종 관련22개도 통과했다.

실제 제공자 등록/실키/모바일 실기기, 운영 TLS·프록시/접속 IP·로그, 키 보관/교체, 운영 DB 배포는 미완료다. FE의 로그아웃5초 미확인/보안 저장 실패/재시작 복원은 FE 검증 범위이며 서버 시험만으로 완료로 표시하지 않는다. 이메일로 계정을 자동 연결하지 않는다.

GitHub CI: PR63/64/65의 verify job은 실행 단계 없이 실패했다. PR65 run40740135583/job122999189049의 annotation은 최근 결제 실패 또는 지출 한도 증가 필요를 명시한다. 로컬196개 통과와 구분하며 계정 결제/한도 설정은 변경하지 않았다. PR66도 CI 성공으로 가정하지 않는다.
