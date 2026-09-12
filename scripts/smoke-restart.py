#!/usr/bin/env python3
"""Verify real HTTP persistence across a JVM restart using a dedicated test DB."""
import base64
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
jars = list((root / 'target').glob('*.jar'))
assert len(jars) == 1, 'Build the application JAR with ./mvnw verify first'
assert os.environ.get('DB_URL') and os.environ.get('DB_PASSWORD'), 'A dedicated test DB is required'
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
base = f'http://127.0.0.1:{port}'
username, password = f'restart-{uuid.uuid4()}', secrets.token_urlsafe(24)
env = {**os.environ, 'SPRING_PROFILES_ACTIVE': 'local', 'SPRING_SECURITY_USER_NAME': username,
       'SPRING_SECURITY_USER_PASSWORD': password}
auth = 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()
java = str(Path(env['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in env else 'java'
logdir = Path(tempfile.mkdtemp(prefix='rufit-restart-'))
process = None
log = None
opener = None
csrf = None

def request(method, path, body=None, key=None):
    headers = {'Authorization': auth}
    if method not in ('GET', 'HEAD'):
        headers[csrf['headerName']] = csrf['token']
    if key:
        headers['Idempotency-Key'] = key
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    response = opener.open(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=10)
    raw = response.read()
    return response.status, json.loads(raw) if raw else None

def start():
    global process, log, opener, csrf
    log = (logdir / f'server-{time.time_ns()}.log').open('w')
    process = subprocess.Popen([java, '-jar', str(jars[0]), f'--server.port={port}'], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(200):
        if process.poll() is not None:
            raise RuntimeError(f'Server exited; logs: {logdir}')
        try:
            with urllib.request.urlopen(base + '/actuator/health', timeout=1) as response:
                if response.status == 200:
                    break
        except (OSError, urllib.error.URLError):
            time.sleep(0.1)
    else:
        raise RuntimeError(f'Server startup timeout; logs: {logdir}')
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    _, csrf = request('GET', '/api/v1/csrf')

def stop():
    global process, log
    if process is not None:
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        process = None
    if log is not None:
        log.close()
        log = None

try:
    start()
    key = str(uuid.uuid4())
    status, workout = request('POST', '/api/v1/workouts', key=key)
    assert status == 201 and workout['id'] == key
    _, workout = request('PUT', f'/api/v1/workouts/{key}/exercises', {
        'version': workout['version'], 'sets': [{'exerciseName': '스쿼트', 'weight': 60.5, 'reps': 10}]})
    _, workout = request('PUT', f'/api/v1/workouts/{key}/sets/0/completion', {
        'version': workout['version'], 'completed': True, 'restSeconds': 90})
    deadline = workout['restEndsAt']
    stop()
    start()
    status, recovered = request('GET', '/api/v1/workouts/active')
    assert status == 200 and recovered['id'] == key
    assert recovered['sets'][0]['weight'] == 60.5 and recovered['completedSetIndexes'] == [0]
    assert recovered['restEndsAt'] == deadline
    _, replay = request('POST', '/api/v1/workouts', key=key)
    assert replay['id'] == key and replay['version'] == recovered['version']
    _, finished = request('POST', f'/api/v1/workouts/{key}/finish', {'version': recovered['version']})
    assert finished['finishedAt'] and finished['restEndsAt'] is None
    assert request('GET', '/api/v1/workouts/active')[0] == 204
    _, history = request('GET', '/api/v1/workouts')
    assert history['totalElements'] == 1 and history['items'][0]['id'] == key
    print('PASS: real HTTP write, JVM restart, active recovery, idempotent retry, finish, history')
finally:
    stop()
