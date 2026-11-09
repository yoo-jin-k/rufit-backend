# 로컬 인증 메일함

사용자 승인에 따라 실제 이메일 발송 서비스 연결 전 Mailpit을 사용합니다.
이 설정은 메일 수신 환경만 준비하며 회원가입 API가 연결됐다는 의미가 아닙니다.

## 실행과 확인

```sh
docker --context desktop-linux compose -f dev/mail/compose.yaml up -d
```

- Mac 브라우저 메일함: http://127.0.0.1:18025
- Mac에서 실행하는 서버 SMTP: 127.0.0.1:11025
- Docker Desktop의 앱 서버에서 접근: host.docker.internal:11025 (연결 여부를 별도 검증합니다.)
- 실제 Gmail/카카오 메일로 전달하지 않습니다. 로컬 메일함에 수신된 인증값을 앱에 입력합니다.
- 메일함 포트는 공개 터널에 넣지 않습니다. DB 볼륨과 메일 본문을 Git에 추가하지 않습니다.
- 정지: 같은 명령의 up -d 대신 stop. 데이터 삭제는 자동으로 수행하지 않습니다.

공식 설치 자료: https://mailpit.axllent.org/docs/install/docker/

## 후속 검증

메일함 구동 확인과 실제 앱의 가입 인증 메일 수신 검증을 구분합니다.
실제 앱의 이메일 가입·재설정 API 및 화면 연결 전에는 가입할 수 없습니다.
