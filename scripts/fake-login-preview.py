#!/usr/bin/env python3
"""FE 전용 가짜 로그인 서버. 시험 DB와 별도의 볼륨/컨테이너만 관리한다."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OWNER = 'rufit-social-login-preview'
DB = 'rufit-auth-preview-db'
API = 'rufit-auth-preview'
VOLUME = 'rufit-auth-preview-pg'
BASE = 'http://127.0.0.1:8096'
ENV = Path(tempfile.gettempdir()) / 'rufit-auth-preview.env'
CACHE = Path('/private/tmp/rufit-auth-m2') if Path('/private/tmp').exists() else Path(tempfile.gettempdir()) / 'rufit-auth-m2'


def run(*args, check=True):
    return subprocess.run(['docker', *args], check=check, text=True, capture_output=True)


def owned(name):
    result = run('inspect', name, check=False)
    if result.returncode:
        return None
    item = json.loads(result.stdout)[0]
    if item.get('Config', {}).get('Labels', {}).get('com.rufit.owner') != OWNER:
        raise RuntimeError('같은 이름의 다른 소유 컨테이너는 변경하지 않습니다: ' + name)
    return item


def env_file():
    if ENV.exists():
        if ENV.is_symlink() or ENV.stat().st_mode & 0o077:
            raise RuntimeError('시험 환경 파일은 소유자만 읽을 수 있어야 합니다.')
        return
    password = secrets.token_urlsafe(32)
    descriptor = os.open(ENV, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write('POSTGRES_DB=rufit_test\nPOSTGRES_USER=rufit_test\nPOSTGRES_PASSWORD=' + password
                     + '\nDB_URL=jdbc:postgresql://localhost:5432/rufit_test\nDB_USERNAME=rufit_test\nDB_PASSWORD=' + password + '\n')


def start():
    existing = owned(API)
    if existing and existing['State']['Running']:
        print('이미 실행 중: ' + BASE)
        return
    env_file()
    volume = run('volume', 'inspect', VOLUME, check=False)
    if volume.returncode:
        run('volume', 'create', '--label', 'com.rufit.owner=' + OWNER, VOLUME)
    elif json.loads(volume.stdout)[0].get('Labels', {}).get('com.rufit.owner') != OWNER:
        raise RuntimeError('다른 소유 볼륨은 사용하지 않습니다.')
    database = owned(DB)
    if not database:
        run('run', '-d', '--name', DB, '--label', 'com.rufit.owner=' + OWNER, '--env-file', str(ENV),
            '-p', '127.0.0.1:8096:8096', '-v', VOLUME + ':/var/lib/postgresql/data', 'postgres:17')
    elif not database['State']['Running']:
        run('start', DB)
    for _ in range(60):
        if run('exec', DB, 'pg_isready', '-h', '127.0.0.1', '-U', 'rufit_test', '-d', 'rufit_test', check=False).returncode == 0:
            break
        time.sleep(0.5)
    else:
        raise RuntimeError('전용 preview DB가 준비되지 않았습니다.')
    CACHE.mkdir(parents=True, exist_ok=True)
    run('run', '--rm', '-d', '--name', API, '--label', 'com.rufit.owner=' + OWNER,
        '--network', 'container:' + DB, '--env-file', str(ENV), '-v', str(ROOT) + ':/workspace',
        '-v', str(CACHE) + ':/root/.m2', '-w', '/workspace', 'maven:3.9-eclipse-temurin-21',
        'mvn', '-B', '-DskipTests', 'spring-boot:test-run', '-Dspring-boot.run.main-class=com.rufit.auth.FakeLoginLauncher')
    for _ in range(90):
        try:
            with urllib.request.urlopen(BASE + '/actuator/health', timeout=1) as response:
                if json.load(response)['status'] == 'UP':
                    print('가짜 로그인 API 준비 완료: ' + BASE)
                    return
        except Exception:
            pass
        time.sleep(0.5)
    raise RuntimeError('preview 기동을 확인하지 못했습니다. docker logs rufit-auth-preview로 확인하세요.')


def stop():
    for name in (API, DB):
        item = owned(name)
        if item and item['State']['Running']:
            run('stop', '--time', '10', name)
    print('preview만 종료했습니다. 전용 DB 볼륨과 FE 데이터는 보존했습니다.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop', 'status'])
    args = parser.parse_args()
    if args.action == 'start':
        start()
    elif args.action == 'stop':
        stop()
    else:
        for name in (API, DB):
            item = owned(name)
            print(name + ': ' + ('running' if item and item['State']['Running'] else 'stopped'))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        # docker 명령의 환경 파일 내용이나 비밀을 오류 출력에 섞지 않는다.
        raise SystemExit(str(error) if isinstance(error, RuntimeError) else 'Docker 작업 실패. 이름 충돌/실행 상태를 확인하세요.')
