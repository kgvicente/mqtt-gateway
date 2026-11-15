#!/usr/bin/env python3
"""Pruebas aisladas de autenticación y adaptadores; JVM o nativo."""
import contextlib
import http.server
import json
import os
import pathlib
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
received = []
health_mode = "up"
verify_count = 0
login_count = 0
admin_token = None

class Backend(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, value):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        try:
            self.wfile.write(value if isinstance(value, bytes) else json.dumps(value).encode())
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        global verify_count
        if self.path == "/api/v5/users":
            verify_count += 1
            return self.reply(200 if self.headers.get("Authorization") == "Bearer smoke-token" else 401, [])
        return self.reply(503 if health_mode == "down" else 200, {"status": health_mode})

    def do_POST(self):
        global login_count
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/api/v5/login":
            login_count += 1
            return self.reply(200, {"token": "smoke-token"}) if body == {"username": "admin", "password": "test-password"} else self.reply(401, {})
        key = self.headers.get("X-EMQX-API-KEY")
        received.append((self.path, body, key))
        mode = body.get("test_mode", "allow")
        if mode == "timeout":
            time.sleep(3)
        if mode == "delay":
            time.sleep(.25)
        if mode == "slow":
            time.sleep(1.1)
        expected = "smoke-backend-api-key" if self.path.startswith("/8090/") else "dynamic-key" if self.path.startswith("/dynamic/") else None
        code = 403 if key != expected else 503 if mode == "error" else 200
        value = b"invalid" if mode == "invalid" else {"result": mode if mode in ("deny", "ignore") else "allow", "is_superuser": False}
        return self.reply(code, value)

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Backend)
server_thread = threading.Thread(target=server.serve_forever, daemon=True)
server_thread.start()

def shutdown_server():
    global server, server_thread
    if server:
        try:
            server.shutdown()
        except Exception:
            pass
        try:
            server.server_close()
        except Exception:
            pass
    if server_thread and server_thread.is_alive():
        try:
            server_thread.join(timeout=2)
        except Exception:
            pass

temporary = tempfile.TemporaryDirectory(prefix="smoke-", dir=root / "target")
data = pathlib.Path(temporary.name) / "data"

def get_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]

smoke_port = int(os.environ.get("GATEWAY_SMOKE_HTTP_PORT") or 0)
if smoke_port == 0 or smoke_port == 18085:
    smoke_port = get_free_port()

base_url = f"http://127.0.0.1:{smoke_port}"
os.environ["GATEWAY_SMOKE_HTTP_PORT"] = str(smoke_port)
os.environ["GATEWAY_BASE_URL"] = base_url

config = (root / "src/main/resources/application.yml").read_text()
config = config.replace("port: 8085", f"port: {smoke_port}")
config = config.replace("${GATEWAY_DATA_DIR:data}", data.as_posix())
config = config.replace("${EMQX_BACKEND_API_KEY_ENV:}", "EMQX_BACKEND_API_KEY")
monitoring_fixture = f"""gateway:
  emqx-url: http://127.0.0.1:{server.server_port}
  probe-seconds: 1
  monitoring:
    probe-interval-seconds: 1
    probe-timeout-ms: 500
    failure-threshold: 1
    recovery-threshold: 1
"""
config = config.replace("gateway:\n", monitoring_fixture)
for port in (8090, 8091, 8080):
    config = config.replace(f"127.0.0.1:{port}/", f"127.0.0.1:{server.server_port}/{port}/")
config_file = pathlib.Path(temporary.name) / "application.yml"
config_file.write_text(config)
def get_java_executable():
    if "JAVA_HOME" in os.environ:
        cand = pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if cand.exists():
            return str(cand)
    which_java = shutil.which("java")
    if which_java:
        return which_java
    return "java"

java_cmd = [get_java_executable()]
command = sys.argv[1:] or (java_cmd + ["-jar", "target/quarkus-app/quarkus-run.jar"])
command.insert(1, "-Dquarkus.config.locations=" + config_file.resolve().as_uri())
MASTER_KEY_FIXTURE = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
env = dict(os.environ, GATEWAY_ADMIN_AUTH_PROVIDER="internal",
           GATEWAY_ADMIN_USERNAME="admin", GATEWAY_ADMIN_PASSWORD="test-password",
           GATEWAY_DATA_DIR=data.as_posix(),
           GATEWAY_MASTER_KEY=MASTER_KEY_FIXTURE,
           EMQX_BACKEND_API_KEY="smoke-backend-api-key")
log = (root / "target/smoke.log").open("w")
process = None

def request(path, method="GET", value=None, token="session", expected=200, password=None):
    body = value.encode() if isinstance(value, str) else json.dumps(value).encode() if value is not None else None
    headers = {"Content-Type": "application/json", "X-EMQX-API-KEY": "untrusted-client-key"}
    if password is not None:
        headers["X-Admin-Password"] = password
        headers["X-Admin-Username"] = "admin"
    if token == "session":
        token = admin_token
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base_url + path, data=body, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        if path.startswith("/api/admin/"):
            assert response.headers.get("Cache-Control") == "no-store"
        assert response.status == expected, (path, response.status, expected, raw[:200])
        return json.loads(raw) if raw else None

def post(endpoint, value):
    return request("/api/v1/gateway/" + endpoint, "POST", value)

def start():
    global process
    process = subprocess.Popen(command, cwd=root, stdout=log, stderr=log, env=env)
    for _ in range(150):
        if process.poll() is not None:
            raise RuntimeError((root / "target/smoke.log").read_text(encoding="utf-8", errors="replace"))
        try:
            req = urllib.request.Request(base_url + "/q/health")
            with urllib.request.urlopen(req, timeout=1) as resp:
                if resp.status == 200:
                    return
        except (OSError, AssertionError, urllib.error.URLError):
            time.sleep(.1)
    raise RuntimeError("Gateway did not start")

def stop():
    if process and process.poll() is None:
        process.terminate()
        process.wait(timeout=10)

def wait_for(predicate, seconds=6):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(.15)
    raise AssertionError("Timed out waiting for state")

try:
    start()
    for path in ("/routes", "/metrics", "/incidents", "/logs", "/auth/verify"):
        request("/api/admin" + path, token=None, expected=401)
        request("/api/admin" + path, token="bad", expected=401)
    request("/api/admin/routes", "POST", {}, token=None, expected=401)
    request("/api/admin/routes/core-service", "PUT", {}, token=None, expected=401)
    request("/api/admin/routes/core-service", "DELETE", token=None, expected=401)
    request("/api/admin/routes/core-service/test", "POST", token=None, expected=401)
    request("/api/admin/auth/login", "POST", {"username": "admin", "password": "bad"}, token=None, expected=401)
    def login():
        return request("/api/admin/auth/login", "POST", {"username": "admin", "password": "test-password"}, token=None)
    request("/api/admin/auth/login", "POST", {"username": "otro", "password": "test-password"}, token=None, expected=401)
    request("/api/admin/auth/login", "POST", {}, token=None, expected=400)
    session = login()
    admin_token = session["token"]
    assert session["username"] == "admin" and len(admin_token) == 36
    assert login()["token"] != admin_token
    assert login_count == 0
    assert request("/api/admin/auth/verify", "POST") == {"valid": True}
    assert request("/api/admin/auth/verify") == {"valid": True}
    # Configurar fixture smoke F2: intervalo 1s, timeout sondeo 500ms, umbrales 1/1 para preservar flow determinista
    try:
        request("/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 1, "probeTimeoutMs": 500, "failureThreshold": 1, "recoveryThreshold": 1}})
    except Exception:
        pass
    before = verify_count
    routes = request("/api/admin/routes")
    assert verify_count == before and len(routes) == 3
    assert all("apiKey" not in r for r in routes) and routes[0]["hasApiKey"]
    with urllib.request.urlopen(f"{base_url}/gateway/") as page:
        assert page.headers.get("X-Frame-Options") == "SAMEORIGIN"
        html = page.read().decode()
        assert ("Panel de MQTT Gateway" in html or "Panel de Gateway" in html) and "<dialog" in html
        assert "<svg" in html and "<dialog" in html
    assert post("auth", {"username": "backend-system-client", "password": "backend-system-password-2026$"}) == {"result": "allow", "is_superuser": True}
    assert post("auth", {"username": "backend-system-client", "password": "bad"}) == {"result": "deny"}
    for topic in ("heart", "ping"):
        assert post("acl", {"topic": topic}) == {"result": "allow", "is_superuser": False}
    for prefix, port in (("core_",8090),("android_",8090),("ios_",8090),("web_",8090),("dev_",8091),("tel_",8080)):
        payload = {"clientid": prefix + "test", "username": "u", "password": "p", "extra": 42}
        assert post("auth", payload)["result"] == "allow"
        assert received[-1] == (f"/{port}/api/v1/emqx/auth", payload, "smoke-backend-api-key" if port == 8090 else None)
    for prefix, port in (("v1/",8090),("core/",8090),("dev/",8091),("tel/",8080)):
        payload = {"clientid": "test", "username": "u", "action": "publish", "topic": prefix + "test", "qos": 1}
        assert post("acl", payload)["result"] == "allow"
        assert received[-1] == (f"/{port}/api/v1/emqx/acl", payload, "smoke-backend-api-key" if port == 8090 else None)
    assert post("auth", {"clientid": "unknown"}) == {"result": "ignore"}
    for malformed in ("{", "null", "[]", "{}", '{"clientid":4}'):
        assert post("auth", malformed) == {"result": "deny"}
    for mode in ("deny", "ignore", "invalid", "error", "timeout"):
        assert post("auth", {"clientid": "core_test", "test_mode": mode})["result"] == (mode if mode in ("deny", "ignore") else "deny")
    # Cada adaptador recorre el mismo núcleo y devuelve su contrato propio.
    for broker in ("emqx", "mosquitto", "vernemq", "generic"):
        for endpoint in ("auth", "acl"):
            for decision in ("allow", "deny", "ignore"):
                payload = {"username": "u", "password": "p", "topic": "core/test", "test_mode": decision}
                payload["client_id" if broker == "vernemq" else "clientid"] = "core_test"
                if endpoint == "acl":
                    payload["acc" if broker == "mosquitto" else "action"] = 2 if broker == "mosquitto" else "publish"
                expected = {"allow": 200, "deny": 400, "ignore": 404}[decision] if broker == "mosquitto" else 200
                response = request(f"/api/v1/gateway/{broker}/{endpoint}", "POST", payload, expected=expected)
                if broker == "generic":
                    assert response == {"allowed": decision == "allow", "status": decision}
                elif broker == "vernemq":
                    assert response == {"result": {"allow": "ok", "deny": {"error": "not_allowed"}, "ignore": "next"}[decision]}
                else:
                    assert response["result"] == decision
                forwarded = received[-1][1]
                assert forwarded["clientid"] == "core_test"
                if endpoint == "acl":
                    assert forwarded["action"] == "publish"
                assert received[-1][2] == "smoke-backend-api-key"
            # Sin ruta y JSON malformado también mantienen el formato del adaptador.
            missing = {"clientid": "unknown", "client_id": "unknown", "topic": "unknown/", "acc": 2}
            response = request(f"/api/v1/gateway/{broker}/{endpoint}", "POST", missing, expected=404 if broker == "mosquitto" else 200)
            assert response.get("status", response.get("result")) == ("next" if broker == "vernemq" else "ignore")
            response = request(f"/api/v1/gateway/{broker}/{endpoint}", "POST", "{", expected=400 if broker == "mosquitto" else 200)
            assert response == ({"allowed": False, "status": "deny"} if broker == "generic" else {"result": {"error": "not_allowed"}} if broker == "vernemq" else {"result": "deny"})
    for acc, action in ((1, "subscribe"), (2, "publish"), (4, "subscribe")):
        assert post("mosquitto/acl", {"clientid": "core_test", "topic": "core/test", "acc": acc})["result"] == "allow"
        assert received[-1][1]["action"] == action
    request("/api/v1/gateway/mosquitto/acl", "POST", {"topic": "ping", "acc": 99}, expected=400)
    import urllib.parse
    form = urllib.parse.urlencode({"clientid": "core_form", "username": "u+ &", "password": "p&+="}).encode()
    req = urllib.request.Request(f"{base_url}/api/v1/gateway/mosquitto/auth", data=form, headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req) as response:
        assert response.status == 200
    assert received[-1][1]["password"] == "p&+=" and received[-1][1]["username"] == "u+ &"
    for mode in ("allow", "deny", "ignore"):
        payload = {"client_id": "core_sub", "username": "u", "topics": [{"topic": "core/a", "qos": 0}, {"topic": "core/b", "qos": 1}], "test_mode": mode}
        before = len(received)
        assert post("vernemq/acl", payload) == {"result": {"allow": "ok", "deny": {"error": "not_allowed"}, "ignore": "next"}[mode]}
        assert len(received) == before + 2
        assert all(p[1]["action"] == "subscribe" for p in received[-2:])
    assert post("vernemq/acl", {"client_id": "core_sub", "topics": []}) == {"result": {"error": "not_allowed"}}
    assert post("vernemq/acl", {"client_id": "core_sub", "topics": [{"topic": "core/a"}, {"topic": "unknown/"}]}) == {"result": "next"}
    assert any(e["client"] == "core_sub" and e["topic"] == "core/a" for e in request("/api/admin/logs"))
    dynamic = {"name": "Smoke dynamic", "enabled": True, "authPrefixes": ["dyn_"],
               "aclPrefixes": ["dynamic/"], "authUrl": f"http://127.0.0.1:{server.server_port}/dynamic/auth",
               "aclUrl": f"http://127.0.0.1:{server.server_port}/dynamic/acl",
               "healthUrl": f"http://127.0.0.1:{server.server_port}/health",
               "apiKey": "dynamic-key"}
    # apiKeyEnv no vacío DEBE ser rechazado con 400
    request("/api/admin/routes", "POST", dict(dynamic, apiKeyEnv="SOME_VAR"), expected=400)
    # apiKey=null DEBE ser rechazado con 400
    request("/api/admin/routes", "POST", dict(dynamic, apiKey=None), expected=400)
    # Crear ruta válida con apiKey write-only
    created = request("/api/admin/routes", "POST", dynamic, expected=201)
    rid = created["id"]
    for path in ("/api/admin/routes/" + rid, "/api/admin/routes?id=" + rid):
        for password in ("bad", ""):
            response = request(path, "DELETE", expected=403, password=password)
            assert response["error"] == "Contraseña de confirmación incorrecta"
        assert any(r["id"] == rid for r in request("/api/admin/routes"))
    assert request("/api/admin/auth/verify") == {"valid": True}
    assert "apiKey" not in created and created["hasApiKey"] is True and "apiKeyEnv" not in created
    assert post("auth", {"clientid": "dyn_test"})["result"] == "allow"
    assert received[-1][2] == "dynamic-key"
    assert post("acl", {"topic": "dynamic/test"})["result"] == "allow"
    # Cadena vacía en apiKey elimina el secreto
    created["apiKey"] = ""
    request("/api/admin/routes/" + rid, "PUT", created)
    assert post("auth", {"clientid": "dyn_test"})["result"] == "deny"
    assert received[-1][2] is None
    # Restaurar apiKey
    created["apiKey"] = "dynamic-key"
    request("/api/admin/routes/" + rid, "PUT", created)
    # Omitir apiKey en edición conserva la referencia previa
    del created["apiKey"]
    created["enabled"] = False
    request("/api/admin/routes/" + rid, "PUT", created)
    assert post("auth", {"clientid": "dyn_test"})["result"] == "ignore"
    created["enabled"] = True
    request("/api/admin/routes", "PUT", created)
    assert post("auth", {"clientid": "dyn_test"})["result"] == "allow"
    assert received[-1][2] == "dynamic-key"
    request("/api/admin/routes", "POST", dict(dynamic, authUrl="file:///etc/passwd"), expected=400)
    request("/api/admin/routes", "POST", dynamic, expected=400)
    wait_for(lambda: not next(m for m in request("/api/admin/metrics") if m["id"] == rid)["awaitingObservation"])
    post("auth", {"clientid": "dyn_test", "test_mode": "delay"})
    m = next(m for m in request("/api/admin/metrics") if m["id"] == rid)
    assert m["status"] == "Degraded" and m["currentMs"] >= 200
    assert 0 <= m["minMs"] <= m["averageMs"] <= m["maxMs"]
    assert m["minMs"] <= m["p95Ms"] <= m["maxMs"]
    post("auth", {"clientid": "dyn_test", "test_mode": "slow"})
    m = next(m for m in request("/api/admin/metrics") if m["id"] == rid)
    assert m["status"] == "Slow" and m["currentMs"] > 1000
    health_mode = "down"
    wait_for(lambda: any(i["backendId"] == rid and not i["endedAt"] for i in request("/api/admin/incidents")))
    incident_count = sum(i["backendId"] == rid for i in request("/api/admin/incidents"))
    request("/api/admin/routes/" + rid + "/test", "POST")
    assert sum(i["backendId"] == rid for i in request("/api/admin/incidents")) == incident_count
    created["enabled"] = False
    request("/api/admin/routes/" + rid, "PUT", created)
    assert any(i["backendId"] == rid and i.get("endReason") == "Monitoring paused" for i in request("/api/admin/incidents"))
    created["enabled"] = True
    request("/api/admin/routes/" + rid, "PUT", created)
    request("/api/admin/routes/" + rid + "/test", "POST")
    wait_for(lambda: any(i["backendId"] == rid and not i["endedAt"] for i in request("/api/admin/incidents")))
    health_mode = "up"
    wait_for(lambda: any(i["backendId"] == rid and i.get("endReason") == "Recovered" and i["durationMs"] > 0 for i in request("/api/admin/incidents")))
    wait_for(lambda: any(m["id"] == rid and m["uptimePercent"] is not None for m in request("/api/admin/metrics")))
    for i in range(205):
        post("acl", {"clientid": f"audit-{i}", "topic": "ping", "password": "never-log-this"})
    events = request("/api/admin/logs")
    assert len(events) >= 200 and events[0]["client"] == "audit-204"
    assert "never-log-this" not in json.dumps(events) and "dynamic-key" not in json.dumps(events)
    import sqlite3
    db_file = data / "gateway.db"
    assert db_file.exists()
    # Esperar que los incidentes asincronos hayan sido confirmados en memoria/API
    wait_for(lambda: any(i["backendId"] == rid for i in request("/api/admin/incidents")))
    time.sleep(0.3)
    with contextlib.closing(sqlite3.connect(db_file)) as conn:
        wal = conn.execute("PRAGMA journal_mode;").fetchone()[0]
        assert wal.lower() == "wal"
        migrations = conn.execute("SELECT version, name, checksum FROM schema_migrations;").fetchall()
        assert len(migrations) >= 1 and migrations[0][0] == 1
        # Comprobar que ninguna tabla de la base de datos contiene secretos
        tables = [r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table';").fetchall()]
        all_db_text = " ".join(str(row) for t in tables for row in conn.execute(f"SELECT * FROM {t};").fetchall())
        assert "dynamic-key" not in all_db_text and "smoke-backend-api-key" not in all_db_text
        incidents_rows = conn.execute("SELECT * FROM incidents WHERE backend_id = ?;", (rid,)).fetchall()
        assert len(incidents_rows) > 0
    stop()
    old_token = admin_token
    start()
    request("/api/admin/auth/verify", token=old_token, expected=401)
    admin_token = login()["token"]
    assert any(r["id"] == rid for r in request("/api/admin/routes"))
    assert request("/api/admin/incidents")
    assert post("auth", {"clientid": "dyn_test"})["result"] == "allow"
    request("/api/admin/routes/" + rid, "DELETE", expected=204, password="test-password")
    assert post("auth", {"clientid": "dyn_test"})["result"] == "ignore"
    assert any(i["backendId"] == rid for i in request("/api/admin/incidents"))
    compatible = request("/api/admin/routes", "POST", dynamic, expected=201)
    request("/api/admin/routes/" + compatible["id"], "DELETE", expected=204)
    # El modo EMQX conserva la delegación y los tokens internos no se aceptan.
    stop()
    env["GATEWAY_ADMIN_AUTH_PROVIDER"] = "emqx"
    start()
    request("/api/admin/auth/verify", token=admin_token, expected=401)
    request("/api/admin/auth/login", "POST", {"username": "admin", "password": "bad"}, token=None, expected=401)
    admin_token = login()["token"]
    assert admin_token == "smoke-token" and login_count > 0
    before = verify_count
    assert request("/api/admin/auth/verify") == {"valid": True}
    assert request("/api/admin/routes") and verify_count > before
    confirmation = request("/api/admin/routes", "POST", dynamic, expected=201)
    request("/api/admin/routes/" + confirmation["id"], "DELETE", expected=403, password="bad")
    request("/api/admin/routes?id=" + confirmation["id"], "DELETE", expected=403, password="bad")
    request("/api/admin/routes?id=" + confirmation["id"], "DELETE", expected=204, password="test-password")
    if os.environ.get("UI_SMOKE") == "1":
        ui_env = dict(os.environ, GATEWAY_BASE_URL=base_url, GATEWAY_SMOKE_HTTP_PORT=str(smoke_port))
        subprocess.run(["node", "scripts/ui-smoke.cjs"], cwd=root, env=ui_env, check=True)
    shutdown_server()
    assert post("auth", {"clientid": "core_test"}) == {"result": "deny"}
    request("/api/admin/metrics", expected=503)
    stop()
    env["GATEWAY_ADMIN_AUTH_PROVIDER"] = "internal"
    start()
    admin_token = login()["token"]
    assert request("/api/admin/metrics")
    assert request("/api/admin/auth/verify") == {"valid": True}
    ready_resp = request("/q/health/ready")
    assert ready_resp["status"] == "UP"
    assert any(c["name"] == "storage" and c["status"] == "UP" for c in ready_resp.get("checks", []))
    print("CORRECTO: confirmación de eliminación interna y EMQX, autenticación interna sin EMQX, tokens aleatorios y reinicio, delegación EMQX, cuatro adaptadores allow/deny/ignore, formularios, suscripciones múltiples, auditoría, CRUD, claves, latencias, sondeos, incidentes, persistencia SQLite WAL y health ready")
finally:
    stop()
    shutdown_server()
    log.close()
    temporary.cleanup()
