# 친구 관계 v1

2026-11-03. 검색 디렉터리의 공개 memberId를 사용하며 인증 principal에서 본인 식별자를 결정합니다. 친구 관계는 운동/사진 공개 동의와 별개입니다.

- POST /api/v1/social/requests `{requestId:UUID,targetId:UUID}` →200 `{id,requesterId,targetId,state,requesterNickname,targetNickname}`. requestId는 클라이언트가 생성하고 네트워크 재시도에 재사용합니다. 같은 요청 재전달은 재생성하지 않습니다. 다른 대상/소유자의 키 재사용, 자기 자신, 이미 친구, 다른 키의 진행 중 신청은409. 상대가 검색 비허용이거나 양방향 차단이면404.
- GET /api/v1/social/requests →200 `{incoming:[Request],outgoing:[Request]}`. 현재 사용자 관련 PENDING만 반환, 각 최대100개. 제한을 넘는 신규 신청은409.
- POST /api/v1/social/requests/{id}/respond `{action:"ACCEPT"|"REJECT"|"CANCEL"}` →200 Request. ACCEPT/REJECT는 받는 사람만, CANCEL은 보낸 사람만 가능. 동일 결과 재전달은 동일값 반환, 다른 종료 상태 변경은409. 무관한 사람은404.
- GET /api/v1/social/friends →200 `{items:[{memberId,nickname}]}` 최대100명. 닉네임·ID 정렬. 친구라도 비공개 운동/게시물은 응답하지 않습니다.
- POST /api/v1/social/blocks/{memberId} →204. 본인 차단 불가400. 차단·친구 관계 제거·양방향 대기 요청 취소를 한 트랜잭션으로 처리. 재전달은 부작용 없이 성공.
- DELETE /api/v1/social/blocks/{memberId} →204. 본인이 만든 차단만 해제. 이전 친구/신청은 복원하지 않음.
- GET /api/v1/social/blocks →200 `{items:[{memberId,nickname}]}`. 본인이 차단한 목록만 노출.

새 관계는 계정/회원 쌍별 잠금으로 순차 처리합니다. 차단은 검색에서 상호 제외하며 친구 요청과 수락에서도 검사합니다. 변경 전후 상태가 다른 오래된 수락 요청은 관계를 되살리지 않습니다. 친구100명/대기100개 상한은 v1 정책입니다.

오류는 기존 ProblemDetail 형식:400 INVALID_INPUT/VALIDATION_FAILED,401 인증,404 대상 없음(차단 여부 숨김),409 STATE_CONFLICT,503 저장소 실패. 친구·차단은 실제 테스트 회원으로만 검증하며 사용자 기존 친구를 취소하지 않습니다.

신고 저장/운영 처리, 푸시·응원·게시물 권한과 UI 연결은 후속 범위입니다.
