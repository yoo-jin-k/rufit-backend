#!/usr/bin/env python3
"""8096의 실제 Spring API+fake OAuth를 시험하고 비밀 없는 결과만 저장한다."""
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import secrets
import urllib.error
import urllib.parse
import urllib.request

BASE = 'http://127.0.0.1:8096'
RESULT = Path(__file__).resolve().parents[1] / 'target/fake-login-http-result.json'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = urllib.request.build_opener(NoRedirect)


def request(path, body=None, token=None):
    url = BASE + path if path.startswith('/') else path
    if not url.startswith(BASE + '/'):
        raise RuntimeError('fixture 서버 밖으로 요청하지 않습니다.')
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = HTTP.open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read(16385)
        if len(raw) > 16384:
            raise RuntimeError('응답 크기 한도 초과')
        return response.status, response.headers, json.loads(raw) if raw else None


def expect(actual, expected, step):
    if actual != expected:
        raise RuntimeError(step + ': 예상 상태 ' + str(expected) + ', 실제 ' + str(actual))


def login(provider='GOOGLE', user='alice'):
    verifier = secrets.token_urlsafe(32)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
    status, _, started = request('/api/v1/auth/login-attempts', {'provider': provider, 'client': 'IOS', 'returnRoute': 'APP_LOGIN', 'codeChallenge': challenge, 'codeChallengeMethod': 'S256'})
    expect(status, 201, 'start')
    status, headers, _ = request(started['authorizationUrl'] + '&fixtureUser=' + user)
    expect(status, 302, 'fake authorize')
    status, headers, _ = request(headers['Location'])
    expect(status, 302, 'callback')
    uri = urllib.parse.urlsplit(headers['Location'])
    if uri.scheme != 'rufit' or uri.netloc != 'auth' or uri.path != '/callback':
        raise RuntimeError('등록된 복귀 주소 불일치')
    query = urllib.parse.parse_qs(uri.query)
    if set(query) != {'attemptId', 'handoffCode'}:
        raise RuntimeError('handoff 필드 불일치')
    status, _, session = request('/api/v1/auth/session-exchanges', {'attemptId': started['attemptId'], 'handoffCode': query['handoffCode'][0], 'codeVerifier': verifier})
    expect(status, 200, 'exchange')
    status, _, account = request('/api/v1/me', token=session['accessToken'])
    expect(status, 200, 'me')
    if account['id'] != session['account']['id']:
        raise RuntimeError('계정 불일치')
    return session


def refresh(session):
    return request('/api/v1/auth/refresh', {'refreshToken': session['refreshToken']})


def logout(session):
    return request('/api/v1/auth/logout', {'refreshToken': session['refreshToken']})


def main():
    sessions = []
    try:
        alice = login(); sessions.append(alice)
        bob = login(user='bob'); sessions.append(bob)
        kakao = login('KAKAO'); sessions.append(kakao)
        if len({s['account']['id'] for s in sessions}) != 3:
            raise RuntimeError('fixture 계정/제공자 구분 실패')
        status, _, rotated = refresh(alice); expect(status, 200, 'refresh'); sessions.append(rotated)
        if rotated['sessionId'] != alice['sessionId'] or rotated['account']['id'] != alice['account']['id']:
            raise RuntimeError('refresh 회원/세션 변경')
        expect(refresh(alice)[0], 401, 'old refresh replay')
        expect(request('/api/v1/me', token=rotated['accessToken'])[0], 401, 'immediate revoke')
        expect(request('/api/v1/me', token=bob['accessToken'])[0], 200, 'other grant survives')
        expect(logout(bob)[0], 204, 'logout'); expect(request('/api/v1/me', token=bob['accessToken'])[0], 401, 'logout revokes access')
        concurrent = login(); sessions.append(concurrent)
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda _: refresh(concurrent), range(2)))
        if sorted(r[0] for r in results) != [200, 401]:
            raise RuntimeError('동시 회전 상태 불일치')
        winner = next(r[2] for r in results if r[0] == 200); sessions.append(winner)
        expect(request('/api/v1/me', token=winner['accessToken'])[0], 401, 'concurrent reuse revoke')
        RESULT.parent.mkdir(parents=True, exist_ok=True)
        RESULT.write_text(json.dumps({'passed': True, 'checkedAt': datetime.now(timezone.utc).isoformat(), 'baseUrl': BASE,
            'provider': 'FAKE_ONLY', 'checks': ['google-start-callback-exchange-me', 'kakao-start-callback-exchange-me', 'account-isolation',
                'refresh-rotation', 'old-refresh-reuse-revocation', 'logout-immediate-revocation', 'concurrent-refresh-revocation']}, ensure_ascii=False, indent=2) + '\n')
        print('PASS: fake 제공자 + 실제 Spring HTTP 로그인/갱신/폐기 검증. 실제 Google/Kakao 로그인 검증 아님.')
    finally:
        # 이 스크립트가 만든 grant만 정리하며 FE의 다른 세션/운동을 초기화하지 않는다.
        for session in sessions:
            try:
                logout(session)
            except Exception:
                pass


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as error:
        raise SystemExit(str(error))
