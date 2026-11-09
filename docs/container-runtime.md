# 백엔드 컨테이너 실행

이미지는 Java 21 실행 환경과 검증된 JAR만 포함합니다. 현재 검증한 Temurin 이미지 digest를 고정했으며 관리자가 아닌 UID 10001로 실행합니다. 소스·테스트·로컬 환경 파일은 빌드 컨텍스트에서 제외합니다. 공개 서버에 배포하거나 외부 로그인 제공자를 등록하는 작업은 이 구성에 포함되지 않습니다.

## 이미지 만들기

테스트 데이터베이스 환경변수를 설정하고 `./mvnw -B verify`를 통과한 다음 `docker build -t rufit-backend:local .`을 실행합니다. Dockerfile은 `target`에 패키징된 JAR 하나를 사용합니다. 애플리케이션 기본 프로필은 건강 상태 확인 외의 업무 API를 차단합니다.

## 계정 서버와 함께 실행

기존 `compose.yaml`은 PostgreSQL만 실행합니다. 실제 발급자를 설정한 계정 서버 실행 구성은 `compose.app.yaml`을 함께 사용합니다.

- DB_PASSWORD: 데이터베이스 비밀번호
- RUFIT_AUTH_ISSUER: 신뢰할 토큰 발급자
- RUFIT_AUTH_JWK_SET_URI: 해당 발급자의 JWKS 주소
- RUFIT_AUTH_AUDIENCE: 서버가 받을 토큰의 audience
- RUFIT_ALLOWED_ORIGINS: 필요한 웹 origin 목록(선택)
- RUFIT_API_PORT: 호스트 HTTP 포트(기본 8080)

설정 후 `docker compose -f compose.yaml -f compose.app.yaml up --build -d`로 시작합니다. 필수 값이 없으면 구성을 실행하지 않습니다. 호스트 연결은 127.0.0.1에 한정합니다. 앱의 외부 연결은 배포 환경의 HTTPS 진입점과 실제 로그인 제공자를 결정한 뒤 연결해야 합니다.

앱 컨테이너는 읽기 전용 파일시스템, 임시 `/tmp`, 512MB 메모리 한도로 실행합니다. 데이터는 PostgreSQL 볼륨에 남습니다. 종료는 같은 compose 파일을 지정한 `down`으로 하며 데이터 볼륨 삭제 옵션은 사용하지 않습니다. 이 구성을 실제 운영 데이터베이스에 연결해 검증하지는 않았습니다.

## 컨테이너 자동 검증

`docker build -t rufit-backend:verify .` 후 테스트 DB 비밀번호와 `RUFIT_TEST_CONTAINER`를 설정하고 `python3 scripts/smoke-container.py`를 실행합니다. 기본 테스트 컨테이너 이름은 `rufit-postgres-verify`입니다.

검증은 테스트 DB에 임시 네트워크를 추가한 뒤 다음을 확인합니다: 비관리자·읽기 전용 실행, 인증 없는 요청 거부, CSRF를 포함한 기록 생성, 컨테이너 재시작 후 복구, 운동 취소, 기본 프로필의 업무 API 차단. 테스트용 계정만 사용하고 임시 앱 컨테이너와 네트워크를 정리합니다. 원래 PostgreSQL 컨테이너와 데이터는 유지합니다. 결과는 `target/container-runtime-result.json`에 남깁니다.

다른 CPU 아키텍처를 에뮬레이션하는 로컬 VM에서는 시작이 느릴 수 있습니다. 이 경우 테스트 실행에만 `RUFIT_CONTAINER_START_TIMEOUT=300`처럼 준비 대기 시간을 지정할 수 있습니다. 기본 대기는 90초이며 기동 중인 서버가 종료되면 즉시 실패합니다.
