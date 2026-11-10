import urllib.request, json

def post(endpoint, data):
    req = urllib.request.Request(f'http://127.0.0.1:8085/api/v1/gateway/{endpoint}',
        data=json.dumps(data).encode('utf-8'),
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as res:
        print(f'{endpoint} with {data.get("clientid")} / {data.get("topic")}:', res.status, json.load(res))

post('auth', {'clientid': 'backend-system-client', 'username': 'backend-system-client', 'password': 'backend-system-password-2026$'})
post('auth', {'clientid': 'core_app', 'username': 'user1', 'password': 'bad'})
post('acl', {'topic': 'heart'})
post('acl', {'topic': 'ping'})
