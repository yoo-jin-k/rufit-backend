#!/usr/bin/env python3
"""Run the verified JAR as a non-root, read-only container against the dedicated test DB."""
import base64
from datetime import datetime, timezone
import http.cookiejar
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
database = os.environ.get('RUFIT_TEST_CONTAINER', 'rufit-postgres-verify')
image = os.environ.get('RUFIT_BACKEND_IMAGE', 'rufit-backend:verify')
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', database):
    raise SystemExit('A test database container name or ID is required')
if not os.environ.get('DB_PASSWORD'):
    raise SystemExit('DB_PASSWORD for rufit_test is required')
suffix = uuid.uuid4().hex
network = 'rufit-runtime-test-' + suffix
app = 'rufit-runtime-app-' + suffix
username, password = 'container-' + suffix, secrets.token_urlsafe(24)
env = {**os.environ, 'DB_USERNAME': 'rufit_test', 'DB_URL': 'jdbc:postgresql://postgres:5432/rufit_test',
       'SPRING_SECURITY_USER_NAME': username, 'SPRING_SECURITY_USER_PASSWORD': password}


def docker(*arguments, timeout=120):
    return subprocess.run(['docker', *arguments], env=env, check=True, timeout=timeout,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout.strip()


def launch(local):
    global running
    command = ['run', '-d', '--name', app, '--network', network, '--publish', '127.0.0.1::8080',
               '--read-only', '--tmpfs', '/tmp:rw,nosuid,noexec,size=128m,mode=1777', '--memory', '512m',
               '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
               '--env', 'DB_URL', '--env', 'DB_USERNAME', '--env', 'DB_PASSWORD']
    if local:
        command += ['--env', 'SPRING_PROFILES_ACTIVE=local', '--env', 'SPRING_SECURITY_USER_NAME', '--env', 'SPRING_SECURITY_USER_PASSWORD']
    docker(*command, image)
    running = True
    mapped = docker('port', app, '8080/tcp')
    if not re.fullmatch(r'127\.0\.0\.1:[0-9]+', mapped):
        raise RuntimeError('Expected an isolated loopback HTTP port')
    return 'http://' + mapped


def wait_ready(base):
    deadline = time.monotonic() + int(os.environ.get('RUFIT_CONTAINER_START_TIMEOUT', '90'))
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base + '/actuator/health', timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, OSError, http.client.HTTPException):
            pass
        if docker('inspect', '--format', '{{.State.Running}}', app) != 'true':
            raise RuntimeError('Application container stopped before becoming healthy')
        time.sleep(0.5)
    raise RuntimeError('Application container health check timed out')


network_created = connected = running = False
try:
    docker('network', 'create', network)
    network_created = True
    docker('network', 'connect', '--alias', 'postgres', network, database)
    connected = True
    base = launch(True)
    running = True
    wait_ready(base)
    uid = docker('exec', app, 'id', '-u')
    if uid == '0':
        raise RuntimeError('Application unexpectedly runs as root')
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    authorization = 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode()
    csrf = {}

    def request(method, path, body=None, authenticated=True):
        headers = {'Accept': 'application/json'}
        if authenticated:
            headers['Authorization'] = authorization
        if method != 'GET' and csrf:
            headers[csrf['headerName']] = csrf['token']
        payload = None if body is None else json.dumps(body).encode()
        if payload is not None:
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(base + '/api/v1' + path, data=payload, headers=headers, method=method)
        try:
            with opener.open(req, timeout=15) as response:
                data = response.read()
                return response.status, json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            return error.code, None

    assert request('GET', '/workouts', authenticated=False)[0] == 401
    _, csrf = request('GET', '/csrf')
    status, workout = request('POST', '/workouts')
    assert status == 201
    docker('restart', app)
    base = 'http://' + docker('port', app, '8080/tcp')
    wait_ready(base)
    status, recovered = request('GET', '/workouts/active')
    assert status == 200 and recovered['id'] == workout['id']
    _, csrf = request('GET', '/csrf')
    assert request('POST', '/workouts/' + workout['id'] + '/cancel', {'version': recovered['version'], 'confirmed': True})[0] == 200
    assert request('GET', '/workouts/active')[0] == 204
    docker('rm', '-f', app)
    running = False
    base = launch(False)
    running = True
    wait_ready(base)
    assert request('GET', '/workouts', authenticated=False)[0] == 403
    result = {'passed': True, 'checkedAt': datetime.now(timezone.utc).isoformat(), 'image': image,
              'imageId': docker('image', 'inspect', '--format', '{{.Id}}', image), 'runtimeUid': uid,
              'checks': ['read-only non-root startup', 'unauthenticated request rejected', 'CSRF-protected write',
                         'container restart recovery', 'cancellation', 'default profile denies business API']}
finally:
    if running and sys.exc_info()[0] is not None:
        logs = subprocess.run(['docker','logs','--tail','150',app], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout
        for line in logs.splitlines():
            if any(marker in line for marker in ['ERROR','Caused by:','Tomcat started','Started Rufit','HikariPool','SIGSEGV','fatal error','Problematic frame','Java VM:','JRE version:']):
                print(line, file=sys.stderr)
        print('Runtime state: ' + docker('inspect','--format','{{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}}',app), file=sys.stderr)
    if running:
        docker('rm', '-f', app)
    if connected:
        docker('network', 'disconnect', network, database)
    if network_created:
        docker('network', 'rm', network)

(root / 'target').mkdir(exist_ok=True)
(root / 'target' / 'container-runtime-result.json').write_text(json.dumps(result, indent=2) + '\n')
print('PASS: non-root read-only container, authenticated write, restart recovery, default-deny profile; temporary runtime removed')
