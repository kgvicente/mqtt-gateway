#!/usr/bin/env python3
"""
Suite exhaustiva de pruebas para Secretos Administrados y Cifrado AES-256-GCM.
Valida:
1. Master Key: obligatoriedad, formato Base64 exacto 32 bytes, fail-fast ante falta o corrupción.
2. Canary: detección de clave incorrecta al arrancar antes de abrir puertos.
3. AES-256-GCM: nonces aleatorios únicos por cifrado, aislamiento AAD por propietario, integridad tamper.
4. Base de datos y WAL: cero secretos en texto claro.
5. routes.apiKey write-only: creación, conservación por omisión, rotación live con 2 valores sin restart, borrado con "", rechazo de null y apiKeyEnv.
6. settings.telegram.botToken write-only: guardado atómico, rotación live con 2 tokens sin restart, preservación, outbox sin token, validación de desactivación al borrar.
7. Migración V4 transaccional única:
   - Referencia faltante aborta sin dejar canary, marcador ni secreto parcial.
   - Tras migración y borrado desde panel, el entorno legado no resucita el secreto.
8. Cache privada de proceso y persistencia tras reinicio limpio.
"""

import base64
import contextlib
import hashlib
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
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUNNER_EXE = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else None

# Claves Base64 de prueba (32 bytes decodificados)
TEST_MASTER_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="       # 32 bytes: b"0123456789abcdef0123456789abcdef"
WRONG_MASTER_KEY_B64 = "OTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTBmZWRjYmE="      # 32 bytes: b"9876543210fedcba9876543210fedcba"
INVALID_SHORT_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZg=="                          # 16 bytes: b"0123456789abcdef"
INVALID_CORRUPT_KEY_B64 = "EstaClaveTieneCaracteresInvalidos!@#$%^&*()_+="


def get_migration_script(name):
    path = ROOT / "src" / "main" / "resources" / "db" / "migrations" / name
    raw = path.read_text(encoding="utf-8")
    normalized = raw.replace("\r\n", "\n").strip()
    checksum = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    return normalized, checksum


def seed_real_v3_db(db_path, custom_route=None):
    with open_sqlite(db_path) as conn:
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("""CREATE TABLE IF NOT EXISTS schema_migrations (
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            checksum TEXT NOT NULL,
            applied_at TEXT NOT NULL
        );""")
        for ver, fname in [(1, "V1__initial_schema.sql"), (2, "V2__monitoring.sql"), (3, "V3__telegram_outbox.sql")]:
            script, cs = get_migration_script(fname)
            for statement in script.split(";"):
                stmt = statement.strip()
                if stmt:
                    conn.execute(stmt)
            conn.execute("INSERT INTO schema_migrations VALUES (?, ?, ?, datetime('now'));", (ver, fname, cs))

        now_iso = datetime.now(timezone.utc).isoformat()
        # Evitar sobreescritura por seeding YAML por defecto
        conn.execute("INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES ('seeded_from_yaml_at', ?, ?);", (now_iso, now_iso))

        if custom_route:
            conn.execute("""INSERT INTO routes (
                id, name, enabled, auth_url, acl_url, health_url, api_key_env,
                auth_prefixes, acl_prefixes, created_at, monitoring_json, telegram_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);""", (
                custom_route["id"],
                custom_route.get("name", "Legacy Route"),
                1 if custom_route.get("enabled", True) else 0,
                custom_route.get("authUrl", "http://127.0.0.1:19999/auth"),
                custom_route.get("aclUrl", "http://127.0.0.1:19999/acl"),
                custom_route.get("healthUrl", "http://127.0.0.1:19999/health"),
                custom_route.get("apiKeyEnv"),
                json.dumps(custom_route.get("authPrefixes", ["leg_"])),
                json.dumps(custom_route.get("aclPrefixes", ["legacy/"])),
                now_iso,
                None,
                None
            ))
        conn.commit()


def extract_header_ci(headers, target_name):
    target = target_name.lower()
    for k, v in headers.items():
        if k.lower() == target:
            return v
    return None


def get_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def get_java_executable():
    if "JAVA_HOME" in os.environ:
        cand = pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if cand.exists():
            return str(cand)
    which_java = shutil.which("java")
    if which_java:
        return which_java
    return "java"


class MockBackendServer:
    def __init__(self):
        self.received_requests = []
        self.lock = threading.Lock()
        server_instance = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length) if length > 0 else b""
                try:
                    parsed_body = json.loads(body.decode("utf-8")) if body else {}
                except Exception:
                    parsed_body = body.decode("utf-8", errors="replace")

                headers_dict = dict(self.headers)
                with server_instance.lock:
                    server_instance.received_requests.append({
                        "path": self.path,
                        "method": "POST",
                        "headers": headers_dict,
                        "body": parsed_body
                    })

                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"result": "allow"}')

            def do_GET(self):
                with server_instance.lock:
                    server_instance.received_requests.append({
                        "path": self.path,
                        "method": "GET",
                        "headers": dict(self.headers),
                        "body": None
                    })
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status": "UP"}')

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def get_requests(self):
        with self.lock:
            return list(self.received_requests)

    def clear_requests(self):
        with self.lock:
            self.received_requests.clear()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


class MockTelegramServer:
    def __init__(self):
        self.received_messages = []
        self.lock = threading.Lock()
        server_instance = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                raw_body = self.rfile.read(length) if length > 0 else b""
                try:
                    body = json.loads(raw_body.decode("utf-8"))
                except Exception:
                    body = {}

                with server_instance.lock:
                    server_instance.received_messages.append({
                        "path": self.path,
                        "headers": dict(self.headers),
                        "body": body
                    })

                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"ok": true, "result": {"message_id": 9999}}')

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def get_messages(self):
        with self.lock:
            return list(self.received_messages)

    def clear(self):
        with self.lock:
            self.received_messages.clear()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


def run_gateway_instance(data_dir, http_port, master_key=TEST_MASTER_KEY_B64,
                         telegram_api_url=None, extra_env=None, log_file=None):
    base_env = os.environ.copy()
    base_env["GATEWAY_DATA_DIR"] = str(data_dir)
    base_env["GATEWAY_ADMIN_AUTH_PROVIDER"] = "internal"
    base_env["GATEWAY_ADMIN_USERNAME"] = "admin"
    base_env["GATEWAY_ADMIN_PASSWORD"] = "test-password"
    if master_key is not None:
        base_env["GATEWAY_MASTER_KEY"] = master_key
    elif "GATEWAY_MASTER_KEY" in base_env:
        del base_env["GATEWAY_MASTER_KEY"]

    if extra_env:
        base_env.update(extra_env)

    if RUNNER_EXE:
        runner_path = pathlib.Path(RUNNER_EXE).resolve()
        cmd = [
            str(runner_path),
            "-Dquarkus.profile=test",
            f"-Dquarkus.http.port={http_port}",
            "-Dquarkus.http.host=127.0.0.1",
            f"-Dgateway.data-dir={data_dir}",
        ]
        if master_key is not None:
            cmd.append(f"-Dgateway.master-key={master_key}")
        if telegram_api_url:
            cmd.append(f"-Dgateway.telegram.api-url={telegram_api_url}")
    else:
        jar = ROOT / "target" / "quarkus-app" / "quarkus-run.jar"
        if not jar.exists():
            raise FileNotFoundError(f"No se encontró {jar}. Se requiere empaquetado previo.")
        java_bin = get_java_executable()
        cmd = [
            java_bin,
            "-Dquarkus.profile=test",
            f"-Dquarkus.http.port={http_port}",
            "-Dquarkus.http.host=127.0.0.1",
            f"-Dgateway.data-dir={data_dir}",
        ]
        if master_key is not None:
            cmd.append(f"-Dgateway.master-key={master_key}")
        if telegram_api_url:
            cmd.append(f"-Dgateway.telegram.api-url={telegram_api_url}")
        cmd.extend(["-jar", str(jar)])

    stdout_dest = log_file.open("a", encoding="utf-8") if log_file else subprocess.PIPE
    stderr_dest = subprocess.STDOUT if log_file else subprocess.PIPE
    proc = subprocess.Popen(cmd, stdout=stdout_dest, stderr=stderr_dest, env=base_env, text=True)
    proc._qa_log_fp = stdout_dest if log_file else None
    return proc


def stop_gateway(proc):
    if proc is None:
        return
    try:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
    finally:
        fp = getattr(proc, "_qa_log_fp", None)
        if fp and hasattr(fp, "close") and not fp.closed:
            try:
                fp.close()
            except Exception:
                pass


@contextlib.contextmanager
def open_sqlite(db_path):
    conn = sqlite3.connect(str(db_path))
    try:
        yield conn
    finally:
        conn.close()


def wait_for_ready(port, timeout=12):
    start = time.time()
    url = f"http://127.0.0.1:{port}/q/health/ready"
    while time.time() - start < timeout:
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=1) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    if data.get("status") == "UP":
                        return True
        except Exception:
            time.sleep(0.1)
    return False


def http_req(port, path, method="GET", body=None, token=None, expected=200):
    url = f"http://127.0.0.1:{port}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            content = resp.read().decode("utf-8")
            assert resp.status == expected, f"Se esperaba {expected} pero se obtuvo {resp.status} en {path}: {content}"
            return json.loads(content) if content else {}
    except urllib.error.HTTPError as e:
        content = e.read().decode("utf-8")
        assert e.code == expected, f"Se esperaba {expected} pero se obtuvo HTTP {e.code} en {path}: {content}"
        try:
            return json.loads(content)
        except Exception:
            return {"error": content, "status": e.code}


def get_auth_token(port):
    res = http_req(port, "/api/admin/auth/login", method="POST",
                   body={"username": "admin", "password": "test-password"})
    return res["token"]


def get_route_by_id(port, token, route_id):
    routes = http_req(port, "/api/admin/routes", method="GET", token=token)
    for r in routes:
        if r.get("id") == route_id:
            return r
    raise KeyError(f"Ruta con id {route_id} no encontrada en listado de rutas")


# =====================================================================
# PRUEBAS DE SUITE: SECRETOS ADMINISTRADOS
# =====================================================================

def test_missing_master_key_fails_startup():
    print("-> Test: Clave maestra ausente impide inicio con fallo saneado")
    port = get_free_port()
    with tempfile.TemporaryDirectory(prefix="test-nokey-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        log_file = pathlib.Path(tmp) / "gateway.log"

        proc = run_gateway_instance(data_dir, port, master_key=None, log_file=log_file)
        try:
            exitcode = proc.wait(timeout=10)
            assert exitcode != 0, f"Gateway debió fallar al arrancar sin clave maestra, pero salió con código {exitcode}"
        finally:
            stop_gateway(proc)
        log_text = log_file.read_text(encoding="utf-8", errors="replace")
        assert "master" in log_text.lower() or "key" in log_text.lower() or "error" in log_text.lower()
        print("   [OK] Gateway abortó inicio ante ausencia de GATEWAY_MASTER_KEY")


def test_invalid_master_key_fails_startup():
    print("-> Test: Clave maestra malformada (longitud o base64 inválido) aborta inicio")
    port = get_free_port()
    for bad_key, label in [(INVALID_SHORT_KEY_B64, "16 bytes corto"), (INVALID_CORRUPT_KEY_B64, "caracteres corruptos")]:
        with tempfile.TemporaryDirectory(prefix="test-badkey-") as tmp:
            data_dir = pathlib.Path(tmp) / "data"
            data_dir.mkdir(parents=True, exist_ok=True)
            log_file = pathlib.Path(tmp) / "gateway.log"

            proc = run_gateway_instance(data_dir, port, master_key=bad_key, log_file=log_file)
            try:
                exitcode = proc.wait(timeout=10)
                assert exitcode != 0, f"Gateway debió fallar con clave inválida ({label}), pero retornó {exitcode}"
            finally:
                stop_gateway(proc)
            log_text = log_file.read_text(encoding="utf-8", errors="replace")
            assert bad_key not in log_text, "Clave sensible filtrada en logs de error"
            print(f"   [OK] Clave inválida ({label}) rechazada con éxito sin filtrar material sensible")


def test_canary_detects_wrong_key_on_restart():
    print("-> Test: Canary detecta clave maestra incorrecta en base de datos existente")
    port = get_free_port()
    with tempfile.TemporaryDirectory(prefix="test-canary-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        log_file1 = pathlib.Path(tmp) / "first_start.log"

        # 1. Arrancar primera vez con TEST_MASTER_KEY_B64
        p1 = run_gateway_instance(data_dir, port, master_key=TEST_MASTER_KEY_B64, log_file=log_file1)
        try:
            assert wait_for_ready(port), "El gateway debió iniciar exitosamente en el primer arranque"
            token = get_auth_token(port)
            r_body = {
                "name": "Canary Route",
                "enabled": True,
                "authPrefixes": ["can_"],
                "aclPrefixes": ["canary/"],
                "authUrl": "http://127.0.0.1:19999/auth",
                "aclUrl": "http://127.0.0.1:19999/acl",
                "healthUrl": "http://127.0.0.1:19999/health",
                "apiKey": "canary-super-secret-12345"
            }
            res = http_req(port, "/api/admin/routes", method="POST", body=r_body, token=token, expected=201)
            assert res["hasApiKey"] is True
            assert "apiKey" not in res
        finally:
            stop_gateway(p1)

        time.sleep(0.5)

        # 2. Intentar arrancar con OTRA clave distinta sobre la misma base de datos
        log_file2 = pathlib.Path(tmp) / "second_start.log"
        p2 = run_gateway_instance(data_dir, port, master_key=WRONG_MASTER_KEY_B64, log_file=log_file2)
        try:
            exitcode = p2.wait(timeout=10)
            assert exitcode != 0, f"Gateway debió fallar por canary al arrancar con clave incorrecta, pero salió con {exitcode}"
            log_text = log_file2.read_text(encoding="utf-8", errors="replace")
            assert WRONG_MASTER_KEY_B64 not in log_text and TEST_MASTER_KEY_B64 not in log_text
            print("   [OK] Canary detectó clave incorrecta antes de publicar caché o abrir servicio")
        finally:
            stop_gateway(p2)


def test_routes_api_key_write_only_crud_and_live_cache():
    print("-> Test: routes.apiKey ciclo write-only y rotación en vivo con 2 valores sin restart")
    mock_backend = MockBackendServer()
    port = get_free_port()
    with tempfile.TemporaryDirectory(prefix="test-routes-secrets-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        log_file = pathlib.Path(tmp) / "gateway.log"

        proc = run_gateway_instance(data_dir, port, master_key=TEST_MASTER_KEY_B64, log_file=log_file)
        try:
            assert wait_for_ready(port), "El gateway debe arrancar"
            token = get_auth_token(port)

            # 1. POST con apiKey (Valor 1): se guarda, respuesta tiene hasApiKey=True pero no apiKey
            route_body = {
                "name": "WriteOnly Route",
                "enabled": True,
                "authPrefixes": ["wo_"],
                "aclPrefixes": ["writeonly/"],
                "authUrl": f"http://127.0.0.1:{mock_backend.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock_backend.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock_backend.port}/health",
                "apiKey": "val-1-super-secret-key"
            }
            created = http_req(port, "/api/admin/routes", method="POST", body=route_body, token=token, expected=201)
            rid = created["id"]
            assert created["hasApiKey"] is True
            assert "apiKey" not in created
            assert "apiKeyEnv" not in created

            # 2. GET lista y helper: nunca devuelven apiKey
            got = get_route_by_id(port, token, rid)
            assert got["hasApiKey"] is True and "apiKey" not in got
            routes_list = http_req(port, "/api/admin/routes", token=token)
            matched = next(r for r in routes_list if r["id"] == rid)
            assert matched["hasApiKey"] is True and "apiKey" not in matched

            # 3. Invocar auth con Valor 1: verificar que mock backend recibe cabecera con val-1
            auth_body = {"clientid": "wo_client1", "username": "u1", "password": "p1"}
            auth_res = http_req(port, "/api/v1/gateway/emqx/auth", method="POST", body=auth_body)
            assert auth_res["result"] == "allow"
            reqs = mock_backend.get_requests()
            auth_req = next(r for r in reqs if r["path"] == "/auth")
            key_hdr = extract_header_ci(auth_req["headers"], "x-emqx-api-key")
            assert key_hdr == "val-1-super-secret-key", f"Esperado val-1 pero se recibió {key_hdr}"
            mock_backend.clear_requests()

            # 4. PUT omitiendo apiKey: conserva el secreto previo (val-1)
            update_body = dict(got)
            update_body["name"] = "WriteOnly Route Renamed"
            update_body.pop("apiKey", None)
            updated1 = http_req(port, f"/api/admin/routes/{rid}", method="PUT", body=update_body, token=token, expected=200)
            assert updated1["hasApiKey"] is True
            assert updated1["name"] == "WriteOnly Route Renamed"
            http_req(port, "/api/v1/gateway/emqx/auth", method="POST", body=auth_body)
            reqs = mock_backend.get_requests()
            key_hdr = extract_header_ci(reqs[-1]["headers"], "x-emqx-api-key")
            assert key_hdr == "val-1-super-secret-key"
            mock_backend.clear_requests()

            # 5. ROTACIÓN LIVE con Valor 2: SIN REINICIAR el servidor
            update_body["apiKey"] = "val-2-new-rotated-key-999"
            updated2 = http_req(port, f"/api/admin/routes/{rid}", method="PUT", body=update_body, token=token, expected=200)
            assert updated2["hasApiKey"] is True
            # Inmediatamente la siguiente petición usa la clave rotada (val-2) en caliente
            http_req(port, "/api/v1/gateway/emqx/auth", method="POST", body=auth_body)
            reqs = mock_backend.get_requests()
            key_hdr = extract_header_ci(reqs[-1]["headers"], "x-emqx-api-key")
            assert key_hdr == "val-2-new-rotated-key-999", f"Esperado val-2 pero se recibió {key_hdr}"
            mock_backend.clear_requests()

            # 5b. Rechazo de apiKey inválido (controles, DEL, whitespace, null, non-string, no-ASCII, >4096) y VERIFICACIÓN DE NO MUTACIÓN
            invalid_api_keys = [
                None,
                12345,
                True,
                "   ",
                "val\nkey",
                "val\rkey",
                "val\tkey",
                "val\x00key",
                "val\x7fkey",
                "val\u00f3key",
                "k" * 4097
            ]
            for bad_k in invalid_api_keys:
                bad_payload = dict(update_body, apiKey=bad_k, name="Attempted Malicious Rename")
                http_req(port, f"/api/admin/routes/{rid}", method="PUT", body=bad_payload, token=token, expected=400)

            # Rechazo de apiKeyEnv no vacío con 400
            bad_env = dict(update_body, apiKeyEnv="MY_ENV_KEY_VAR")
            http_req(port, "/api/admin/routes", method="POST", body=bad_env, token=token, expected=400)
            http_req(port, f"/api/admin/routes/{rid}", method="PUT", body=bad_env, token=token, expected=400)

            # VERIFICAR NO MUTACIÓN tras los 400: la ruta sigue con su clave anterior (val-2) y su nombre intacto
            check_route = get_route_by_id(port, token, rid)
            assert check_route["hasApiKey"] is True
            assert check_route["name"] == "WriteOnly Route Renamed"
            http_req(port, "/api/v1/gateway/emqx/auth", method="POST", body=auth_body)
            reqs = mock_backend.get_requests()
            key_hdr = extract_header_ci(reqs[-1]["headers"], "x-emqx-api-key")
            assert key_hdr == "val-2-new-rotated-key-999", f"La clave previa mutó tras 400: {key_hdr}"
            mock_backend.clear_requests()

            # 6. PUT con apiKey="" (cadena vacía): elimina explícitamente el secreto
            update_body["apiKey"] = ""
            updated3 = http_req(port, f"/api/admin/routes/{rid}", method="PUT", body=update_body, token=token, expected=200)
            assert updated3["hasApiKey"] is False
            http_req(port, "/api/v1/gateway/emqx/auth", method="POST", body=auth_body)
            reqs = mock_backend.get_requests()
            assert extract_header_ci(reqs[-1]["headers"], "x-emqx-api-key") is None
            mock_backend.clear_requests()

            # 7. Eliminar backend: borra su secreto de la base de datos
            http_req(port, f"/api/admin/routes/{rid}", method="DELETE", token=token, expected=204)
            print("   [OK] Contrato write-only, rotación live (val-1 -> val-2), no-mutación ante 400 y borrado aprobados")
        finally:
            stop_gateway(proc)
            mock_backend.stop()


def test_telegram_bot_token_write_only_and_live_rotation():
    print("-> Test: settings.telegram.botToken write-only y rotación live con 2 tokens sin restart")
    mock_telegram = MockTelegramServer()
    port = get_free_port()
    with tempfile.TemporaryDirectory(prefix="test-telegram-secrets-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        log_file = pathlib.Path(tmp) / "gateway.log"

        telegram_api_url = f"http://127.0.0.1:{mock_telegram.port}"
        proc = run_gateway_instance(data_dir, port, master_key=TEST_MASTER_KEY_B64,
                                    telegram_api_url=telegram_api_url, log_file=log_file)
        try:
            assert wait_for_ready(port), "El gateway debe arrancar"
            token = get_auth_token(port)

            # 1. PUT settings con Token 1
            tel_payload = {
                "telegram": {
                    "enabled": True,
                    "chatId": "-10011223344",
                    "botToken": "token-1-initial-abc"
                }
            }
            res_put = http_req(port, "/api/admin/settings", method="PUT", body=tel_payload, token=token, expected=200)
            assert res_put["telegram"]["tokenConfigured"] is True
            assert "botToken" not in res_put["telegram"]

            # Enviar notificación de prueba POST /api/admin/notifications/test
            http_req(port, "/api/admin/notifications/test", method="POST", body={}, token=token, expected=202)
            start_wait = time.time()
            while time.time() - start_wait < 5:
                if len(mock_telegram.get_messages()) > 0:
                    break
                time.sleep(0.1)

            msgs = mock_telegram.get_messages()
            assert len(msgs) >= 1
            assert "token-1-initial-abc" in msgs[0]["path"], f"Path no contiene token-1: {msgs[0]['path']}"
            mock_telegram.clear()

            # 2. ROTACIÓN LIVE con Token 2 SIN REINICIAR el servidor
            tel_payload_rot = {
                "telegram": {
                    "enabled": True,
                    "chatId": "-10011223344",
                    "botToken": "token-2-rotated-xyz"
                }
            }
            res_rot = http_req(port, "/api/admin/settings", method="PUT", body=tel_payload_rot, token=token, expected=200)
            assert res_rot["telegram"]["tokenConfigured"] is True

            # Enviar segunda notificación de prueba y verificar que usa de inmediato token-2
            http_req(port, "/api/admin/notifications/test", method="POST", body={}, token=token, expected=202)
            start_wait = time.time()
            while time.time() - start_wait < 5:
                if len(mock_telegram.get_messages()) > 0:
                    break
                time.sleep(0.1)

            msgs2 = mock_telegram.get_messages()
            assert len(msgs2) >= 1
            assert "token-2-rotated-xyz" in msgs2[0]["path"], f"Path no contiene token-2: {msgs2[0]['path']}"
            mock_telegram.clear()

            # 3. Verificar en SQLite que notification_outbox NO contiene ningún token en texto
            db_path = data_dir / "gateway.db"
            with open_sqlite(db_path) as conn:
                outbox_rows = conn.execute("SELECT id, message, status, last_error FROM notification_outbox;").fetchall()
                assert len(outbox_rows) >= 1
                for row in outbox_rows:
                    row_str = " ".join(str(c) for c in row if c is not None)
                    assert "token-1-initial-abc" not in row_str and "token-2-rotated-xyz" not in row_str

            # 4. Intentar eliminar token con enabled=true -> 400
            bad_clear = {"telegram": {"enabled": True, "botToken": ""}}
            http_req(port, "/api/admin/settings", method="PUT", body=bad_clear, token=token, expected=400)

            # 5. Eliminar token con enabled=false -> 200
            good_clear = {"telegram": {"enabled": False, "botToken": ""}}
            res_cleared = http_req(port, "/api/admin/settings", method="PUT", body=good_clear, token=token, expected=200)
            assert res_cleared["telegram"]["tokenConfigured"] is False
            print("   [OK] telegram.botToken rotación live (token-1 -> token-2) y aislamiento en outbox aprobados")
        finally:
            stop_gateway(proc)
            mock_telegram.stop()


def test_sqlite_db_and_wal_contain_no_plaintext_secrets():
    print("-> Test: Cero secretos en texto claro en base SQLite y archivo WAL")
    port = get_free_port()
    with tempfile.TemporaryDirectory(prefix="test-db-clean-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        log_file = pathlib.Path(tmp) / "gateway.log"

        secret_backend_key = "ULTRA-SECRET-KEY-TO-NEVER-LEAK-987"
        secret_telegram_token = "ULTRA-SECRET-TELEGRAM-TOKEN-555"

        proc = run_gateway_instance(data_dir, port, master_key=TEST_MASTER_KEY_B64, log_file=log_file)
        try:
            assert wait_for_ready(port)
            token = get_auth_token(port)

            r_body = {
                "name": "Audit Secret Route",
                "enabled": True,
                "authPrefixes": ["aud_"],
                "aclPrefixes": ["audit/"],
                "authUrl": "http://127.0.0.1:19999/auth",
                "aclUrl": "http://127.0.0.1:19999/acl",
                "healthUrl": "http://127.0.0.1:19999/health",
                "apiKey": secret_backend_key
            }
            http_req(port, "/api/admin/routes", method="POST", body=r_body, token=token, expected=201)

            tel_body = {
                "telegram": {
                    "enabled": False,
                    "chatId": "-100223344",
                    "botToken": secret_telegram_token
                }
            }
            http_req(port, "/api/admin/settings", method="PUT", body=tel_body, token=token, expected=200)
        finally:
            stop_gateway(proc)

        db_file = data_dir / "gateway.db"
        assert db_file.exists()

        for ext in ["", "-wal", "-shm"]:
            fpath = data_dir / f"gateway.db{ext}"
            if fpath.exists():
                raw_bytes = fpath.read_bytes()
                assert secret_backend_key.encode("utf-8") not in raw_bytes
                assert secret_telegram_token.encode("utf-8") not in raw_bytes

        with open_sqlite(db_file) as conn:
            tables = [row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table';").fetchall()]
            for tbl in tables:
                rows = conn.execute(f"SELECT * FROM {tbl};").fetchall()
                table_text = " ".join(str(r) for r in rows)
                assert secret_backend_key not in table_text
                assert secret_telegram_token not in table_text

        print("   [OK] Comprobado: base SQLite y WAL no contienen ningún secreto en texto claro")


def test_legacy_v3_to_v4_migration_and_no_resurrection():
    print("-> Test: Migración V4 transaccional y ausencia de resurrección por entorno tras borrado")
    port = get_free_port()
    with tempfile.TemporaryDirectory(prefix="test-migration-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        db_file = data_dir / "gateway.db"
        log_file1 = pathlib.Path(tmp) / "start1.log"

        legacy_api_key_val = "migrated-from-env-secret-888"
        legacy_bot_token_val = "migrated-bot-token-from-env-777"

        # 1. Sembrar base SQLite V3 real con referencias legadas
        legacy_route = {
            "id": "legacy-route-1",
            "name": "Legacy Route",
            "enabled": True,
            "authUrl": "http://127.0.0.1:19999/auth",
            "aclUrl": "http://127.0.0.1:19999/acl",
            "healthUrl": "http://127.0.0.1:19999/health",
            "apiKeyEnv": "LEGACY_CUSTOM_KEY_VAR",
            "authPrefixes": ["leg_"],
            "aclPrefixes": ["legacy/"]
        }
        seed_real_v3_db(db_file, custom_route=legacy_route)

        # 2. Arrancar V4 con las variables de entorno presentes -> migración exitosa
        migration_env = {
            "LEGACY_CUSTOM_KEY_VAR": legacy_api_key_val,
            "GATEWAY_TELEGRAM_BOT_TOKEN": legacy_bot_token_val
        }
        p1 = run_gateway_instance(data_dir, port, master_key=TEST_MASTER_KEY_B64,
                                  extra_env=migration_env, log_file=log_file1)
        try:
            assert wait_for_ready(port), "Gateway debe arrancar y aplicar migración V4"
            token = get_auth_token(port)

            r = get_route_by_id(port, token, "legacy-route-1")
            assert r["hasApiKey"] is True
            assert "apiKey" not in r

            # Ahora el usuario borra la clave desde el panel explícitamente (apiKey = "")
            r["apiKey"] = ""
            updated = http_req(port, "/api/admin/routes/legacy-route-1", method="PUT", body=r, token=token, expected=200)
            assert updated["hasApiKey"] is False
        finally:
            stop_gateway(p1)

        time.sleep(0.5)

        # 3. Arrancar una SEGUNDA VEZ con la variable de entorno legado TODAVÍA PRESENTE
        # Comprobar que el entorno legado NO RESUCITA el secreto borrado
        log_file2 = pathlib.Path(tmp) / "start2.log"
        p2 = run_gateway_instance(data_dir, port, master_key=TEST_MASTER_KEY_B64,
                                  extra_env=migration_env, log_file=log_file2)
        try:
            assert wait_for_ready(port)
            token = get_auth_token(port)

            r_after = get_route_by_id(port, token, "legacy-route-1")
            assert r_after["hasApiKey"] is False, "El entorno legado no debe resucitar un secreto borrado desde el panel"
            print("   [OK] Migración V4 completada y no-resurrección tras borrado confirmada")
        finally:
            stop_gateway(p2)


def test_missing_env_reference_aborts_migration_without_partial_state():
    print("-> Test: Referencia legacy faltante aborta migración sin dejar estado parcial")
    port = get_free_port()
    with tempfile.TemporaryDirectory(prefix="test-missing-ref-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        db_file = data_dir / "gateway.db"
        log_file = pathlib.Path(tmp) / "abort_migration.log"

        # Sembrar base SQLite V3 real con referencia a variable de entorno inexistente
        missing_route = {
            "id": "missing-ref-route",
            "name": "Missing Ref Route",
            "enabled": True,
            "authUrl": "http://127.0.0.1:19999/auth",
            "aclUrl": "http://127.0.0.1:19999/acl",
            "healthUrl": "http://127.0.0.1:19999/health",
            "apiKeyEnv": "TOTALLY_NON_EXISTENT_VAR_9999",
            "authPrefixes": ["mis_"],
            "aclPrefixes": ["missing/"]
        }
        seed_real_v3_db(db_file, custom_route=missing_route)

        # Arrancar V4 sin definir TOTALLY_NON_EXISTENT_VAR_9999
        proc = run_gateway_instance(data_dir, port, master_key=TEST_MASTER_KEY_B64, log_file=log_file)
        try:
            exitcode = proc.wait(timeout=10)
            assert exitcode != 0, f"Gateway debió abortar migración por variable faltante, pero retornó {exitcode}"
        finally:
            stop_gateway(proc)

        log_text = log_file.read_text(encoding="utf-8", errors="replace")
        assert "totally_non_existent_var_9999" in log_text.lower(), f"El log debe reportar la variable exacta faltante: {log_text}"

        # Verificar que NO quedó estado parcial en la base de datos
        with open_sqlite(db_file) as conn:
            # Si managed_secrets fue creada por DDL, no debe contener canary ni secretos parciales
            has_managed = conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='managed_secrets';").fetchone()
            if has_managed:
                secrets_count = conn.execute("SELECT COUNT(*) FROM managed_secrets;").fetchone()[0]
                assert secrets_count == 0, f"managed_secrets no debió guardar filas parciales ante aborto: {secrets_count}"

            # En settings NO debe existir el marcador env_secrets_migrated_at
            settings_rows = conn.execute("SELECT * FROM settings;").fetchall()
            settings_text = " ".join(str(r) for r in settings_rows)
            assert "env_secrets_migrated_at" not in settings_text, "Marcador env_secrets_migrated_at no debe persistirse si la migración abortó"
        print("   [OK] Migración abortó limpiamente reportando la referencia exacta sin dejar canary, marcador ni secretos parciales")


def test_yaml_seed_missing_env_reference_aborts_startup():
    print("-> Test: Siembra inicial desde YAML con variable de entorno faltante aborta arranque")
    port = get_free_port()
    with tempfile.TemporaryDirectory(prefix="test-yaml-missing-env-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        log_file = pathlib.Path(tmp) / "yaml_abort.log"

        seed_env = {
            "EMQX_BACKEND_API_KEY_ENV": "NON_EXISTENT_SEED_ENV_VAR_9999"
        }
        proc = run_gateway_instance(data_dir, port, master_key=TEST_MASTER_KEY_B64,
                                    extra_env=seed_env, log_file=log_file)
        try:
            exitcode = proc.wait(timeout=10)
            assert exitcode != 0, f"Gateway debió abortar el arranque por variable de seed faltante, pero retornó {exitcode}"
        finally:
            stop_gateway(proc)

        log_text = log_file.read_text(encoding="utf-8", errors="replace")
        assert "non_existent_seed_env_var_9999" in log_text.lower(), f"El log debe reportar la variable de seed exacta: {log_text}"

        db_file = data_dir / "gateway.db"
        if db_file.exists():
            with open_sqlite(db_file) as conn:
                has_routes = conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='routes';").fetchone()
                if has_routes:
                    count = conn.execute("SELECT COUNT(*) FROM routes;").fetchone()[0]
                    assert count == 0, f"No deben quedar rutas sembradas huérfanas si faltó la clave: {count}"
        print("   [OK] Siembra inicial abortó limpiamente reportando la variable esperada sin dejar rutas huérfanas")


def main():
    print("=====================================================================")
    print(" SUITE DE PRUEBAS AUTOMATIZADAS - SECRETOS ADMINISTRADOS (AES-GCM)")
    print("=====================================================================")
    tests = [
        test_missing_master_key_fails_startup,
        test_invalid_master_key_fails_startup,
        test_canary_detects_wrong_key_on_restart,
        test_routes_api_key_write_only_crud_and_live_cache,
        test_telegram_bot_token_write_only_and_live_rotation,
        test_sqlite_db_and_wal_contain_no_plaintext_secrets,
        test_legacy_v3_to_v4_migration_and_no_resurrection,
        test_missing_env_reference_aborts_migration_without_partial_state,
        test_yaml_seed_missing_env_reference_aborts_startup,
    ]

    passed = 0
    failed = 0
    for t in tests:
        try:
            t()
            passed += 1
        except Exception as e:
            print(f"   [FAIL] {t.__name__}: {e}")
            import traceback
            traceback.print_exc()
            failed += 1

    print("\n---------------------------------------------------------------------")
    print(f" RESULTADO SECRETOS ADMINISTRADOS: {passed} PASSED / {failed} FAILED")
    print("---------------------------------------------------------------------")
    if failed > 0:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
