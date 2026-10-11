"""Verify deployed MCP HTTP/stdio discovery and skill loading; never log tokens."""
from pathlib import Path
import json
import requests

ROOT = Path(__file__).resolve().parent
BASE = 'http://127.0.0.1:13000'
s = requests.Session()
auth = s.post(BASE + '/api/v1/auth/login', json=json.loads((ROOT / 'runtime/credentials.json').read_text()), timeout=15)
auth.raise_for_status()
token = auth.json()['access_token']
s.headers['Authorization'] = 'Bearer ' + token
mcp = s.get(BASE + '/api/v1/runtime/mcp', timeout=70).json()
assert not mcp['errors'] and len(mcp['tools']) == 12, mcp
skills = s.get(BASE + '/api/v1/runtime/skills', timeout=15).json()
assert any(x['name'] == 'literature-review' for x in skills['items']), skills

rpc = requests.Session()
rpc.headers.update({'Accept': 'application/json, text/event-stream', 'Content-Type': 'application/json'})
def request(method, params=None, number=None):
    body = {'jsonrpc': '2.0', 'method': method}
    if params is not None:
        body['params'] = params
    if number is not None:
        body['id'] = number
    r = rpc.post(BASE + '/mcp', json=body, timeout=40)
    r.raise_for_status()
    if 'Mcp-Session-Id' in r.headers:
        rpc.headers['Mcp-Session-Id'] = r.headers['Mcp-Session-Id']
    if not r.content:
        return {}
    if r.headers.get('content-type', '').startswith('text/event-stream'):
        messages = [json.loads(line[5:].strip()) for line in r.text.splitlines() if line.startswith('data:')]
        value = next(x for x in messages if x.get('id') == number)
    else:
        value = r.json()
    assert 'error' not in value, value
    return value.get('result', {})

initial = request('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                               'clientInfo': {'name': 'java-deployment-acceptance', 'version': '1'}}, 1)
rpc.headers['Mcp-Protocol-Version'] = initial['protocolVersion']
request('notifications/initialized')
tools = request('tools/list', {}, 2)['tools']
assert len(tools) == 5, len(tools)
notebook = json.loads((ROOT / 'docker-acceptance-results.json').read_text(encoding='utf-8'))['notebook_id']
result = request('tools/call', {'name': 'list_notebook_sources', 'arguments': {'access_token': token, 'notebook_id': notebook}}, 3)
assert not result.get('isError'), result
try:
    rpc.delete(BASE + '/mcp', timeout=10)
finally:
    summary = {'external_stdio_tools': len(mcp['tools']), 'external_stdio_errors': mcp['errors'],
               'skills': [x['name'] for x in skills['items']], 'exposed_http_tools': len(tools),
               'authenticated_http_tool_call': 'passed'}
    (ROOT / 'runtime-acceptance-results.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary))
