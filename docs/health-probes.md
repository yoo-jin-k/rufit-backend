# 서버 상태 확인

모든 실행 프로필에서 인증 없이 다음 상태만 조회할 수 있습니다. DB 주소, 오류 내용, 구성요소 정보는 응답에 포함하지 않습니다. 다른 관리 API는 공개하지 않습니다.

| 경로 | 용도 | DB 연결 중단 시 |
| --- | --- | --- |
| `/actuator/health/liveness` | 서버 자체의 생존 상태 | 서버가 정상 동작 중이면 200 UP 유지 |
| `/actuator/health/readiness` | 기록 요청을 처리할 준비 | 503 DOWN |
| `/actuator/health` | 전체 건강 상태 | 503 DOWN |

루핏의 업무 API는 PostgreSQL에 의존하므로 준비 상태에 DB 확인을 포함합니다. 프로세스 재시작 판단에는 생존 상태를, 트래픽 전달 판단에는 준비 상태를 사용합니다. 모든 인스턴스가 같은 DB를 공유하면 DB 장애 동안 모든 준비 상태가 실패할 수 있으므로, 실제 배포 시 진입점의 대기·실패 응답 정책을 함께 정해야 합니다. 이 변경은 배포나 자동 재시작 정책 자체를 설정하지 않습니다.

앱이 요청을 받지 않는 종료 준비 상태에서는 readiness가 503 OUT_OF_SERVICE가 됩니다. DB가 다시 연결되면 같은 JVM에서 readiness가 UP으로 돌아오는지 기존 DB 중단 검증 스크립트에서 확인합니다. 검증은 전용 테스트 DB만 중단하며 종료 시 다시 시작합니다.

설정 근거: [Spring Boot 공식 상태 확인 문서](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes). 생존 상태에 외부 DB를 포함하지 않고, 준비 상태에 포함할지는 앱 의존성을 기준으로 선택합니다.
