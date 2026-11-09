# 기록 목록의 DB 조회 횟수

완료 기록 20개 페이지에서 각 운동의 세트와 완료 표시를 따로 읽어 총 42개 SQL 문이 실행되는 문제를 실제 PostgreSQL 테스트로 재현했습니다. `hibernate.default_batch_fetch_size=32`를 적용해 이미 조회한 페이지의 컬렉션을 묶어 읽습니다. 응답 필드나 정렬, 페이지 크기, 소유권 조건은 바꾸지 않습니다.

`HistoryQueryBatchingTests`는 본인 완료 기록 40개와 다른 사용자 기록 5개를 저장한 뒤 새 서비스 트랜잭션에서 측정합니다. 현재 전체 이력은 4개, 날짜별 이력은 4개, 종목별 이력은 3개 SQL 문입니다. 각 항목의 세트 값과 완료 표시, 페이지 순서와 총 개수도 검사합니다. 세부 SQL 형태가 달라져도 소규모 고정 횟수 이내에서 유지되도록 상한 8개를 검사합니다.

이는 SQL 왕복 횟수 회귀 검사이며 실제 사용자 부하에서의 지연시간이나 처리량 측정은 아닙니다. 이미 적용한 REPEATABLE_READ 조회와 동시 저장 테스트도 전체 검증에 포함됩니다. Hibernate 통계는 해당 테스트 동안에만 켜고 이후 원래 상태로 되돌립니다.

설정 근거: [Hibernate 7.4 FetchSettings](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/cfg/FetchSettings.html).
