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

## 2026-11-04 관계 관리와 공개 프로필
- DELETE /api/v1/social/friends/{memberId} →204. 인증한 본인과 대상 사이의 관계만 제거합니다. 없는 관계의 반복 요청도204, 존재하지 않는 대상404, 본인 대상400. 오래된 ACCEPT 재시도로 관계가 복원되지 않습니다.
- GET /api/v1/social/members/{memberId} →200 `{memberId,nickname,relationship}`. relationship은 SELF/FRIEND/INCOMING/OUTGOING/NONE입니다. 공개 검색 허용 또는 기존 친구/대기 신청 관계만 조회 가능하며 양방향 차단은404입니다. 비공개 운동/사진/계정 식별자는 제공하지 않습니다.
- POST /api/v1/social/reports `{requestId:UUID,targetId:UUID,reason:"SPAM"|"HARASSMENT"|"INAPPROPRIATE"|"OTHER"}` →200 `{id:UUID,status:"RECEIVED"}`. 본인 신고400, 조회 가능한 대상 또는 본인이 차단한 대상만 가능하며 그 외404. 동일 신고번호와 내용 재시도는 동일 접수 결과, 다른 사용자/내용으로 재사용409. 본인당 하루 최대20건, 초과409. 신고는 차단과 별개이며 운영 검토 완료를 뜻하지 않습니다. 신고 내용/신고자 목록 조회 API는 공개하지 않습니다.
- CORS 허용 메서드에 DELETE를 추가하여 웹의 차단 해제·친구 해제가 작동하도록 합니다. 인증 및 기존 ProblemDetail 오류 형식은 유지합니다.
