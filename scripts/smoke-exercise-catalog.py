#!/usr/bin/env python3
"""실제 preview의 전체 공용 운동과 검색을 확정 JSON에 대조한다. DB를 초기화하지 않는다."""
import importlib.util
import json
from pathlib import Path
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('social_smoke', ROOT / 'scripts/smoke-social-login.py')
social = importlib.util.module_from_spec(spec)
spec.loader.exec_module(social)


def query(path, session):
    # 인증 JSON의16KiB 제한을 운동100개 업무 응답에 적용하지 않는다. 업무 조회도 별도 한도를 둔다.
    request = urllib.request.Request(social.BASE + path, headers={'Authorization': 'Bearer ' + session['accessToken']})
    with social.HTTP.open(request, timeout=10) as response:
        social.expect(response.status, 200, 'catalog query')
        raw = response.read(262145)
        if len(raw) > 262144:
            raise RuntimeError('카탈로그 응답 크기 한도 초과')
        return json.loads(raw)


def main():
    session = social.login()
    try:
        source = json.loads((ROOT / 'src/main/resources/catalog/exercises-v2.json').read_text())['exercises']
        items = []
        pages = 0
        while True:
            data = query('/api/v1/exercises?size=100&page=' + str(pages), session)
            items.extend(data['items'])
            pages += 1
            if not data['hasNext']:
                break
            if pages >= 100:
                raise RuntimeError('카탈로그 페이지 한도 초과')
        actual = {e['id']: e for e in items if not e['custom']}
        if len(items) != len({e['id'] for e in items}) or len(actual) != len(source) or len(source) != 110:
            raise RuntimeError('카탈로그 수량/중복 불일치')
        for row in source:
            for key in ('id', 'name', 'bodyPart', 'equipment', 'recommendedRestSeconds'):
                if actual.get(row['id'], {}).get(key) != row[key]:
                    raise RuntimeError('확정 원본과 API의 필드 불일치: ' + key)
        for search, name in [('사레레', '덤벨 사이드 레터럴 레이즈'), ('케이블 크로스오버', '케이블 체스트 플라이'), ('ＤＵＭＢＢＥＬＬ＿ＦＬＹ', '덤벨 플라이')]:
            data = query('/api/v1/exercises?' + urllib.parse.urlencode({'query': search}), session)
            if not any(e['name'] == name for e in data['items']):
                raise RuntimeError('공용 별칭 검색 실패')
        result = {'passed': True, 'baseUrl': social.BASE, 'exerciseCount': len(actual), 'pageCount': pages,
                  'catalogVersion': '2026-10-29.v2', 'sourceFieldsMatch': True, 'aliasChecks': 3}
        (ROOT / 'target/catalog-http-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print('PASS: 실제 HTTP 공용110종/페이지가 원본과 일치, 한글·영문·전각 별칭 검색 통과.')
    finally:
        social.logout(session)


if __name__ == '__main__':
    main()
