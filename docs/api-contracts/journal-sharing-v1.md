# 오운완 계정 저장·친구 공개·좋아요 v1

모든 요청은 Bearer 인증이 필요합니다. 친구 화면에서 검색용 닉네임을 저장한 계정을 사용합니다. 공개 기본값 PRIVATE, 선택 FRIENDS. 양방향 차단과 현재 친구 관계를 매 조회마다 확인합니다. 기기 기록을 자동 업로드하지 않습니다.

- PUT `/api/v1/social/posts/mine/{sourceId}`: 본인 운동 식별자(1~120자)별 저장. `{version:0, memo:"최대 200자", date:"2026-11-04", summary:"최대 160자", visibility:"PRIVATE", photos:["data:image/jpeg;base64,..."]}`. 사진 선택 0~5장, 장당 원본 2MB 이하 JPG/PNG, 최대 1600만 화소. 서버에서 긴 변1280px 이하 JPEG로 재인코딩하여 메타데이터를 제거합니다. 요청 본문 최대 14,100,000 bytes. sourceId는 운동 원본의 서버 동기화를 뜻하지 않으며 날짜·요약만 명시적으로 업로드합니다.
- GET `/posts/mine/{sourceId}`: 본인 저장본, 없으면404.
- GET `/posts/{id}`: 권한 확인한 상세. 응답 `{id,sourceId,memberId,nickname,memo,date,summary,visibility,version,photos:[dataURI],thumbnail:dataURI|null,likes,liked,mine}`. 타인에게 sourceId는 null.
- GET `/posts?memberId=UUID&page=0`: 해당 회원 게시물 최대12개 최신순. memberId 생략은 본인. `{items:[상세와 같되 photos:[]],hasNext}`. 비공개·차단·비친구 게시물 제외, 페이지는0~1000.
- PUT `/posts/{id}/like`: `{liked:true}` → 상세. 본인 게시물 좋아요 금지400. 현재 친구에게 공개된 게시물만 가능, 비허용404. 계정별 한 개이며 같은 설정 재시도는 중복되지 않습니다.
- POST `/posts/{id}/hide`: 본인 게시물 공개 범위를 PRIVATE로 즉시 변경,204. 삭제 없이 친구 접근을 차단하며 버전을 증가시킵니다.

저장은 sourceId별 잠금과 version으로 동시 수정409 방지, 동일 내용 재시도는 저장본 반환. 성공 전까지 작성 내용 유지. 오류는 기존 ProblemDetail 400/401/404/409, 본문 크기 초과413.

사진은 초기 버전에서 PostgreSQL에 보관하며 계정당 최대200게시물로 제한합니다. 객체 저장소/CDN 이전은 출시 전 후속 작업입니다. 공개 URL은 만들지 않습니다.

좋아요는 게시물 작성자에게 POST_LIKED 알림을 같은 트랜잭션으로 기록합니다. 같은 사용자의 좋아요 취소는 해당 알림 제거, 재좋아요는 새 알림 한 개. 비공개 전환·친구 해제·차단 시 알림을 숨깁니다. 알림 응답에 postId(UUID|null)를 추가하고 클릭 시 게시물 상세로 이동합니다. 기존 친구 알림은 requestId가 유지되고 postId=null입니다.
