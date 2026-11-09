# 친구 검색용 공개 디렉터리 v1

기준일 2026-11-03. 기존 비공개 프로필과 별도의 동의 기반 검색 정보입니다. 기존 자료를 자동 공개하지 않습니다.

## 인증과 공개 범위
모든 경로는 기존 인증 principal을 사용합니다. 요청 owner나 이메일을 받지 않으며 검색 응답에 내부 계정 ID·이메일·소개·운동 기록을 노출하지 않습니다. 서버가 만든 무작위 memberId(UUID)와 사용자가 등록한 nickname만 검색에 사용합니다. 등록 전 기본은 검색 비허용입니다. 닉네임은 중복 가능하며 식별자는 UUID입니다.

## 계약
- GET /api/v1/social/directory/me → 200 `{memberId:UUID|null,nickname:string|null,discoverable:boolean,version:number}`. 미등록은 null/null/false/0.
- PUT /api/v1/social/directory/me 요청 `{nickname:string,discoverable:boolean,version:number}` → 200 동일 형식. nickname 앞뒤 공백 제거 후 2~30자, version>=0. 최초 version0, 이후 현재 version. 같은 값 재요청은 버전 증가 없이 현재값 반환. 다른 값의 오래된 버전은409. 검색 허용은 명시적으로 true를 보낸 경우에만 적용.
- GET /api/v1/social/directory/search?q=닉네임&page=0&size=20 → 200 `{items:[{memberId,nickname}],page,size,hasNext}`. 검색어2~30자, page0~1000,size1~50. 닉네임 부분 일치(대소문자 무시), 닉네임·UUID 정렬. %와 _는 와일드카드가 아니라 문자로 검색. 자신과 비허용 회원 제외. 결과 개수 상한+1개로 다음 페이지 판단.

## 오류와 재시도
기존 ProblemDetail `{type,title,status,detail,code}` 사용.400 INVALID_INPUT/VALIDATION_FAILED,401 인증 필요,409 STATE_CONFLICT,503 STORAGE_UNAVAILABLE. PUT은 동일 본문 재시도 가능. 충돌 시 재조회 후 사용자가 선택하여 변경합니다.

## 범위
친구 관계·차단·응원·게시물 공개는 다음 계약에서 추가합니다. 현재 디렉터리는 프론트에 자동 연결하지 않으며 기존 비공개 프로필 설정을 변경하지 않습니다. 차단 기능 연동 전 실서비스 친구 검색 공개 기능으로 배포하지 않습니다.
