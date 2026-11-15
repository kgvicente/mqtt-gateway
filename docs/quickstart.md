# Try MQTT Gateway locally

[English](quickstart.md) | [Español](quickstart.es.md) | [Project overview](../README.md)

Prefer a real MQTT broker demo? Use the [Docker Compose guide](compose.md).

Run the gateway with a small HTTP backend and test authentication and topic access.
No MQTT broker, external database server, Docker installation or EMQX account is needed for this
HTTP demonstration. It does not establish a real MQTT connection.

Use Linux or WSL with JDK 25, Python 3, Git and curl. The current state store uses
POSIX file permissions. Windows can build the JAR, but native Windows execution
is not currently supported. Maven Wrapper downloads Maven and dependencies.

## 1. Get the source and start the demo backend

In terminal 1:

```sh
git clone https://github.com/kgvicente/mqtt-gateway.git
cd mqtt-gateway
python3 examples/demo_backend.py
```

The backend binds to `127.0.0.1:19090`. Its fixed credentials are
`demo-user` / `demo-password`. They are deliberately limited to this example.

## 2. Build and start the gateway

In terminal 2, from the same repository directory:

Load your cryptographic master key from your private persisted `master.env` file outside the repository (created once following the [secure procedure in the Telegram guide](telegram-docker.es.md#2-preparación-de-la-clave-maestra-masterenv)). If `data/gateway.db` already exists with encrypted secrets, always reusing the persisted key ensures the cryptographic canary check passes at startup:

On Linux / macOS (Bash):

```sh
# 1. Load the master key from the private persisted file outside the repository:
master_env_path="${XDG_CONFIG_HOME:-$HOME/.config}/mqtt-gateway/master.env"
# If not yet created, generate it once following the Telegram guide
set -a
. "$master_env_path"
set +a

# 2. Administrative authentication variables:
export GATEWAY_ADMIN_AUTH_PROVIDER=internal
export GATEWAY_ADMIN_USERNAME=admin
export GATEWAY_ADMIN_PASSWORD='change-this-local-demo-password'

# 3. Build and start the gateway
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

*(On Windows PowerShell: load persisted `master.env` into the process without printing the key:)*

```powershell
$masterEnvPath = Join-Path $env:USERPROFILE '.config\mqtt-gateway\master.env'
Get-Content $masterEnvPath | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
        [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
$env:GATEWAY_ADMIN_AUTH_PROVIDER = 'internal'
$env:GATEWAY_ADMIN_USERNAME = 'admin'
$env:GATEWAY_ADMIN_PASSWORD = 'change-this-local-demo-password'
./mvnw.cmd package
java -jar target/quarkus-app/quarkus-run.jar
```

> [!NOTE]
> `GATEWAY_MASTER_KEY` is a mandatory 32-byte Base64 key for AES-256-GCM encryption in SQLite. Keeping it in the private persisted `master.env` file outside the repository ensures it is preserved across shell sessions and restarts without exposing secrets in logs or console.

Open <http://127.0.0.1:8085/gateway/> and sign in as `admin` using the password
set above. Documentation is available at <http://127.0.0.1:8085/gateway/docs>.

The three preconfigured backend routes may appear offline if their services are
not running. That does not prevent you from adding the demo backend.

## 3. Register the demo route

In terminal 3:

```sh
TOKEN=$(curl --fail-with-body -sS http://127.0.0.1:8085/api/admin/auth/login \
  -H 'Content-Type: application/json' \
  --data '{"username":"admin","password":"change-this-local-demo-password"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl --fail-with-body -sS http://127.0.0.1:8085/api/admin/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"name":"Local demo","enabled":true,"authPrefixes":["demo_"],"aclPrefixes":["demo/"],"authUrl":"http://127.0.0.1:19090/auth","aclUrl":"http://127.0.0.1:19090/acl","healthUrl":"http://127.0.0.1:19090/health"}'
```

Alternatively, use **Registrar backend** in the panel and enter those same URLs
and prefixes. If the route already exists, edit it instead of registering it again.

## 4. Check allow and deny

These requests target the generic adapter. Its response contains `allowed` and
`status`; the backend itself returns the shared `result` contract.

```sh
# Valid credentials: {"allowed":true,"status":"allow"}
curl --fail-with-body -sS http://127.0.0.1:8085/api/v1/gateway/generic/auth \
  -H 'Content-Type: application/json' \
  --data '{"clientid":"demo_sensor","username":"demo-user","password":"demo-password"}'

# Wrong password: {"allowed":false,"status":"deny"}
curl --fail-with-body -sS http://127.0.0.1:8085/api/v1/gateway/generic/auth \
  -H 'Content-Type: application/json' \
  --data '{"clientid":"demo_sensor","username":"demo-user","password":"wrong"}'

# Allowed topic/action: {"allowed":true,"status":"allow"}
curl --fail-with-body -sS http://127.0.0.1:8085/api/v1/gateway/generic/acl \
  -H 'Content-Type: application/json' \
  --data '{"clientid":"demo_sensor","username":"demo-user","topic":"demo/temperature","action":"publish"}'

# Unmatched route: {"allowed":false,"status":"ignore"}
curl --fail-with-body -sS http://127.0.0.1:8085/api/v1/gateway/generic/auth \
  -H 'Content-Type: application/json' \
  --data '{"clientid":"unmatched_sensor","username":"demo-user","password":"demo-password"}'
```

Field order in JSON responses may differ. Select **Probar conexión** for the demo
route to see its health status, then open **Detalle** to inspect probes and audit
events. At the default 30-second probe interval, history fills gradually.

## 5. Stop or reset the demonstration

Use Ctrl+C in terminals 1 and 2. Routes and incidents remain in embedded SQLite (`data/gateway.db`); restart
to continue using them. From the panel, pause or delete the demo route when done.
The UI asks for the configured administrator password when deleting a route.

The demo backend binds to loopback and implements fixed example policies. Replace
it with your own authentication and authorization service for a deployment.
Configure your own administrator and MQTT superuser credentials before exposing
the gateway beyond your local machine.

## Automated checks

From the repository directory, with the JAR already built:

```sh
python3 scripts/smoke.py
```

The smoke suite launches an isolated gateway on port 18085 with temporary state
and mock HTTP dependencies. It does not need the demo backend or an MQTT broker.

## License

Project-owned code is available under [GNU AGPL-3.0](../LICENSE).
Commercial uses outside its permissions require a separate license from the
rights holder. Third-party components retain their [own licenses](../NOTICE).
