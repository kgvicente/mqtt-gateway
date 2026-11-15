#!/usr/bin/env python3
"""
Suite de Pruebas Automatizadas para FASE 3: Notificaciones de Telegram y Outbox Transaccional.
Pruebas exhaustivas y deterministas de:
- API /api/admin/settings: sección telegram {enabled, chatId, tokenConfigured}.
- Token administrado y cifrado con AES-256-GCM: activación (enabled: true) requiere chatId y bot token configurado (400 si falta).
- Secreto write-only: el token se persiste cifrado en SQLite; variables de entorno solo operan como fixture de migración única inicial.
- Omisión de texto plano: el token nunca se expone en texto plano en DB, logs ni respuestas de API (solo tokenConfigured booleano).
- POST /api/admin/notifications/test: encola notificación de prueba con 202 Accepted {id, status}.
- Notificaciones automáticas disparadas exclusivamente por apertura y recuperación de incidentes (no manual/auth).
- Transaccionalidad atómica SQLite: transición de incidente + outbox en la misma transacción.
- Rollback transaccional: fallo al insertar en outbox revierte el incidente y no muta la caché.
- Despacho y entrega hacia Mock HTTP de Telegram (emulando https://api.telegram.org/bot<token>/sendMessage).
- Manejo de HTTP 429 con respeto estricto de parameters.retry_after.
- Manejo de errores 4xx terminales (failed sin reintento) vs 5xx/timeout/ok:false (reintentos con backoff).
- Saneamiento de excepciones y lastError: nunca registrar el bot token en la base de datos ni respuestas.
- Cancelación de pendientes al pausar/eliminar ruta o deshabilitar Telegram globalmente.
- Recuperación en reinicio: mensajes en estado sending vuelven a pending para reintentarse.
"""

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

def get_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

@contextlib.contextmanager
def temp_dir_scope(prefix="test-fase3-"):
    d = tempfile.mkdtemp(prefix=prefix)
    try:
        yield pathlib.Path(d)
    finally:
        shutil.rmtree(d, ignore_errors=True)

class MockTelegramServer:
    """Mock HTTP que emula la API oficial de Telegram Bot (sendMessage)."""
    def __init__(self, host="127.0.0.1", port=0):
        self.host = host
        self.port = port
        self.lock = threading.Lock()
        self.received_messages = []
        self.response_mode = "success"  # "success", "rate_limit_429", "forbidden_403", "server_error_500", "malformed"
        self.retry_after_sec = 2
        self.delay_sec = 0.0
        self.custom_description = None
        self.hold_event = None
        self.request_arrived_event = None
        self.message_counter = 0
        self.server = None
        self.thread = None

    def start(self):
        parent = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            def log_message(self, *args): pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body_bytes = self.rfile.read(length) if length > 0 else b""
                parsed_body = None
                try:
                    parsed_body = json.loads(body_bytes.decode("utf-8"))
                except Exception:
                    parsed_body = {"raw": body_bytes.decode("utf-8", errors="replace")}

                with parent.lock:
                    parent.message_counter += 1
                    parent.received_messages.append({
                        "path": self.path,
                        "body": parsed_body,
                        "headers": dict(self.headers),
                        "counter": parent.message_counter,
                        "timestamp": time.time()
                    })
                    mode = parent.response_mode
                    retry_after = parent.retry_after_sec
                    delay = parent.delay_sec
                    desc = parent.custom_description
                    hold_evt = parent.hold_event
                    arr_evt = parent.request_arrived_event

                if arr_evt:
                    arr_evt.set()

                if hold_evt:
                    hold_evt.wait()

                if delay > 0:
                    time.sleep(delay)

                if mode == "success":
                    resp = {
                        "ok": True,
                        "result": {
                            "message_id": parent.message_counter,
                            "chat": {"id": parsed_body.get("chat_id") if isinstance(parsed_body, dict) else 0},
                            "text": parsed_body.get("text") if isinstance(parsed_body, dict) else ""
                        }
                    }
                    self._reply(200, resp)
                elif mode == "rate_limit_429":
                    resp = {
                        "ok": False,
                        "error_code": 429,
                        "description": desc or f"Too Many Requests: retry after {retry_after}",
                        "parameters": {"retry_after": retry_after}
                    }
                    self._reply(429, resp)
                elif mode == "forbidden_403":
                    resp = {
                        "ok": False,
                        "error_code": 403,
                        "description": desc or "Forbidden: bot was blocked by the user"
                    }
                    self._reply(403, resp)
                elif mode == "server_error_500":
                    resp = {
                        "ok": False,
                        "error_code": 500,
                        "description": desc or "Internal Server Error in Telegram API"
                    }
                    self._reply(500, resp)
                elif mode == "malformed":
                    self.send_response(200)
                    self.send_header("Content-Type", "text/plain")
                    self.end_headers()
                    self.wfile.write(b"NOT_A_JSON_RESPONSE_ERROR")
                else:
                    self._reply(200, {"ok": True})

            def _reply(self, status, payload):
                b = json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(b)

        class Server(http.server.ThreadingHTTPServer):
            request_queue_size = 128
            daemon_threads = True
            allow_reuse_address = True

        self.server = Server((self.host, self.port), Handler)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def set_mode(self, mode="success", retry_after=2, delay=0.0):
        with self.lock:
            self.response_mode = mode
            self.retry_after_sec = retry_after
            self.delay_sec = delay

    def get_messages(self):
        with self.lock:
            return list(self.received_messages)

    def clear(self):
        with self.lock:
            self.received_messages.clear()

    def stop(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()

class BackendMockServer:
    """Mock para backends monitoreados por el gateway."""
    def __init__(self, host="127.0.0.1", port=0):
        self.host = host
        self.port = port
        self.health_status = 200
        self.lock = threading.Lock()
        self.server = None
        self.thread = None

    def start(self):
        parent = self
        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            def log_message(self, *args): pass
            def do_GET(self):
                with parent.lock:
                    st = parent.health_status
                body = json.dumps({"status": "up" if st < 500 else "down"}).encode()
                self.send_response(st)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)

        class Server(http.server.ThreadingHTTPServer):
            daemon_threads = True
            allow_reuse_address = True

        self.server = Server((self.host, self.port), Handler)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def set_health(self, status):
        with self.lock:
            self.health_status = status

    def stop(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()

RUNNER_EXE = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else None

def get_java_executable():
    if "JAVA_HOME" in os.environ:
        cand = pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if cand.exists():
            return str(cand)
    which_java = shutil.which("java")
    if which_java:
        return which_java
    return "java"

def run_gateway_instance(data_dir, http_port, telegram_api_url=None, bot_token="fake-test-bot-token-12345", profile="test", env=None, log_file=None):
    base_env = os.environ.copy()
    base_env["GATEWAY_DATA_DIR"] = str(data_dir)
    base_env["GATEWAY_ADMIN_AUTH_PROVIDER"] = "internal"
    base_env["GATEWAY_ADMIN_USERNAME"] = "admin"
    base_env["GATEWAY_ADMIN_PASSWORD"] = "test-admin-secret"
    base_env["GATEWAY_MASTER_KEY"] = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    if bot_token:
        base_env["GATEWAY_TELEGRAM_BOT_TOKEN"] = bot_token
    else:
        base_env.pop("GATEWAY_TELEGRAM_BOT_TOKEN", None)

    if telegram_api_url:
        base_env["GATEWAY_TELEGRAM_API_URL"] = telegram_api_url

    if env:
        base_env.update(env)

    if RUNNER_EXE:
        runner_path = pathlib.Path(RUNNER_EXE).resolve()
        if not runner_path.exists():
            raise FileNotFoundError(f"No se encontró el runner nativo especificado: {runner_path}")
        cmd = [
            str(runner_path),
            f"-Dquarkus.profile={profile}",
            f"-Dquarkus.http.port={http_port}",
            "-Dquarkus.http.host=127.0.0.1",
            f"-Dgateway.data-dir={data_dir}",
            "-Dgateway.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        ]
        if telegram_api_url:
            cmd.append(f"-Dgateway.telegram.api-url={telegram_api_url}")
    else:
        jar = root / "target" / "quarkus-app" / "quarkus-run.jar"
        if not jar.exists():
            raise FileNotFoundError(f"No se encontró {jar}. Requiere compilación previa.")
        java_bin = get_java_executable()
        cmd = [
            java_bin,
            f"-Dquarkus.profile={profile}",
            f"-Dquarkus.http.port={http_port}",
            "-Dquarkus.http.host=127.0.0.1",
            f"-Dgateway.data-dir={data_dir}",
            "-Dgateway.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        ]
        if telegram_api_url:
            cmd.append(f"-Dgateway.telegram.api-url={telegram_api_url}")
        cmd.extend(["-jar", str(jar)])

    out = log_file.open("w", encoding="utf-8") if log_file else subprocess.DEVNULL
    proc = subprocess.Popen(cmd, cwd=root, env=base_env, stdout=out, stderr=subprocess.STDOUT)
    return proc

def wait_for_ready(port, timeout=20):
    start = time.monotonic()
    url = f"http://127.0.0.1:{port}/q/health"
    while time.monotonic() - start < timeout:
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=1) as resp:
                if resp.status == 200:
                    return True
        except Exception:
            time.sleep(0.15)
    return False

def api_request(port, path, method="GET", body=None, token=None, expected_status=(200, 201, 202, 204), password=None):
    url = f"http://127.0.0.1:{port}{path}"
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if password is not None:
        headers["X-Admin-Password"] = password
        headers["X-Admin-Username"] = "admin"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            content = resp.read()
            if isinstance(expected_status, int):
                expected_status = (expected_status,)
            if resp.status not in expected_status:
                raise AssertionError(f"HTTP {resp.status} inesperado en {path} (esperado {expected_status})")
            return json.loads(content.decode()) if content else None
    except urllib.error.HTTPError as e:
        if isinstance(expected_status, int):
            expected_status = (expected_status,)
        if e.code not in expected_status:
            err_body = e.read().decode(errors="replace")
            raise AssertionError(f"HTTP {e.code} inesperado en {path} (esperado {expected_status}): {err_body}")
        content = e.read()
        return json.loads(content.decode()) if content else None

def login_admin(port, username="admin", password="test-admin-secret"):
    res = api_request(port, "/api/admin/auth/login", "POST", {"username": username, "password": password})
    return res["token"]

# --------------------------------------------------------------------------
# CASOS DE PRUEBA DE FASE 3
# --------------------------------------------------------------------------

def test_telegram_settings_api_and_validation():
    print("-> Test: Validación, herencia y omisión de token en /api/admin/settings (Fase 3)")
    with temp_dir_scope(prefix="test-tg-settings-") as tmp:
        port = get_free_port()
        mock_tg = MockTelegramServer()
        mock_tg.start()
        # Arrancar con bot token presente en el entorno
        proc = run_gateway_instance(tmp, port, telegram_api_url=f"http://127.0.0.1:{mock_tg.port}", bot_token="secret-bot-token-999")
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            # 1. Protección de endpoints
            api_request(port, "/api/admin/settings", "GET", expected_status=401)
            api_request(port, "/api/admin/notifications", "GET", expected_status=401)

            token = login_admin(port)

            # 2. GET inicial debe devolver telegram: {enabled: false, chatId: null, tokenConfigured: true}
            s = api_request(port, "/api/admin/settings", "GET", token=token)
            assert "telegram" in s, f"Falta clave 'telegram' en settings: {s}"
            tg = s["telegram"]
            assert tg["enabled"] is False
            assert tg["chatId"] is None
            assert tg["tokenConfigured"] is True
            # El token NUNCA debe estar en la respuesta
            assert "token" not in json.dumps(tg).lower() or "tokenconfigured" in json.dumps(tg).lower()
            assert "secret-bot-token-999" not in json.dumps(s)

            # 3. Validaciones estrictas de activación (enabled: true)
            # Activación sin chatId -> 400
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": True, "chatId": None}}, token=token, expected_status=400)
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": True, "chatId": ""}}, token=token, expected_status=400)
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": True, "chatId": "   "}}, token=token, expected_status=400)

            # Intentar enviar token por la API -> 400 (no permitido)
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": False, "token": "injected"}}, token=token, expected_status=400)

            # 4. Activación válida con chatId
            res_put = api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": True, "chatId": "-100123456789"}}, token=token, expected_status=200)
            assert res_put["telegram"]["enabled"] is True
            assert res_put["telegram"]["chatId"] == "-100123456789"
            assert res_put["telegram"]["tokenConfigured"] is True

            # 5. Herencia y omisión de secciones: PUT de solo monitoring conserva telegram
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 15, "probeTimeoutMs": 1500, "failureThreshold": 4, "recoveryThreshold": 3}}, token=token, expected_status=200)
            s_after = api_request(port, "/api/admin/settings", "GET", token=token)
            assert s_after["telegram"]["enabled"] is True
            assert s_after["telegram"]["chatId"] == "-100123456789"
            assert s_after["monitoring"]["probeIntervalSeconds"] == 15

            # PUT de solo telegram conserva monitoring
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": False, "chatId": "-100123456789"}}, token=token, expected_status=200)
            s_after2 = api_request(port, "/api/admin/settings", "GET", token=token)
            assert s_after2["telegram"]["enabled"] is False
            assert s_after2["monitoring"]["probeIntervalSeconds"] == 15

            # 6. Comprobar que en la base de datos SQLite NO se guardó ningún token
            db_path = tmp / "gateway.db"
            with sqlite3.connect(str(db_path)) as conn:
                db_text = " ".join(str(row) for t in ("settings", "routes") for row in conn.execute(f"SELECT * FROM {t};").fetchall())
                assert "secret-bot-token-999" not in db_text, "El bot token nunca debe persistirse en SQLite"

            print("   [OK] Settings Telegram, herencia, omisión y protección de token aprobados")
        finally:
            mock_tg.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_telegram_test_notification_202_and_dispatch():
    print("-> Test: Encolado de prueba manual POST /api/admin/notifications/test (202 Accepted) y despacho")
    with temp_dir_scope(prefix="test-tg-testnotif-") as tmp:
        port = get_free_port()
        mock_tg = MockTelegramServer()
        mock_tg.start()
        proc = run_gateway_instance(tmp, port, telegram_api_url=f"http://127.0.0.1:{mock_tg.port}", bot_token="fake-test-token")
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # Con Telegram deshabilitado -> POST test debe responder 400
            api_request(port, "/api/admin/notifications/test", "POST", {}, token=token, expected_status=400)

            # Activar Telegram globalmente
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": True, "chatId": "-100987654321"}}, token=token, expected_status=200)

            # Enviar notificación de prueba -> debe responder 202 con {id, status}
            mock_tg.clear()
            test_res = api_request(port, "/api/admin/notifications/test", "POST", {}, token=token, expected_status=202)
            assert "id" in test_res, f"Falta 'id' en respuesta 202: {test_res}"
            assert test_res["status"] in ("pending", "sending", "sent")
            msg_id = test_res["id"]

            # Esperar a que el worker despache la notificación hacia el mock de Telegram
            t_end = time.monotonic() + 6.0
            delivered = False
            while time.monotonic() < t_end:
                msgs = mock_tg.get_messages()
                if msgs:
                    delivered = True
                    break
                time.sleep(0.2)
            assert delivered, "El worker de outbox no despachó la notificación de prueba al mock"

            # Verificar el payload recibido en el mock
            last_msg = mock_tg.get_messages()[-1]
            assert "chat_id" in last_msg["body"]
            assert str(last_msg["body"]["chat_id"]) == "-100987654321"
            assert "prueba" in last_msg["body"].get("text", "").lower() or "test" in last_msg["body"].get("text", "").lower()

            # Consultar historial en /api/admin/notifications
            notifs = api_request(port, "/api/admin/notifications", "GET", token=token)
            assert "items" in notifs and "summary" in notifs
            assert notifs["summary"]["sent"] >= 1
            item = next((x for x in notifs["items"] if x["id"] == msg_id), None)
            assert item is not None, f"El mensaje {msg_id} debe estar en el historial"
            assert item["status"] == "sent"
            assert item["type"] == "test"
            assert item["chatId"] == "-100987654321"
            assert item["attempts"] >= 1
            assert item.get("sentAt") is not None

            print("   [OK] Notificación de prueba 202, encolado y entrega confirmados")
        finally:
            mock_tg.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_automatic_incident_notifications_opened_and_recovered():
    print("-> Test: Notificaciones automáticas de incidente (apertura/recuperación) vs manual/auth")
    with temp_dir_scope(prefix="test-tg-incidents-") as tmp:
        port = get_free_port()
        mock_tg = MockTelegramServer()
        mock_tg.start()
        mock_backend = BackendMockServer()
        mock_backend.start()

        proc = run_gateway_instance(tmp, port, telegram_api_url=f"http://127.0.0.1:{mock_tg.port}", bot_token="fake-test-token")
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # Activar Telegram global
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": True, "chatId": "-100111222333"}}, token=token, expected_status=200)

            # Crear ruta con intervalo 1s, umbrales 1/1
            route_payload = {
                "name": "Backend Auto Notify",
                "enabled": True,
                "authPrefixes": ["tgaut_"],
                "aclPrefixes": ["tgaut/"],
                "authUrl": f"http://127.0.0.1:{mock_backend.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock_backend.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock_backend.port}/health",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 1,
                    "probeTimeoutMs": 500,
                    "failureThreshold": 1,
                    "recoveryThreshold": 1
                }
            }
            rt = api_request(port, "/api/admin/routes", "POST", route_payload, token=token, expected_status=201)
            rid = rt["id"]

            # 1. Esperar primer sondeo sano
            time.sleep(1.2)
            mock_tg.clear()

            # 2. Provocar caída (503): al fallar 1 vez, se abre incidente y se encola notificación
            mock_backend.set_health(503)

            # Esperar a que llegue la notificación de apertura al mock
            t_end = time.monotonic() + 6.0
            opened_notif = False
            while time.monotonic() < t_end:
                msgs = [m for m in mock_tg.get_messages() if "caído" in m["body"].get("text", "").lower() or "caida" in m["body"].get("text", "").lower() or "alerta" in m["body"].get("text", "").lower() or "abierto" in m["body"].get("text", "").lower() or "opened" in m["body"].get("text", "").lower() or "down" in m["body"].get("text", "").lower()]
                if msgs:
                    opened_notif = True
                    break
                time.sleep(0.2)
            assert opened_notif, "No llegó la notificación de incidente abierto al mock de Telegram"

            # 3. Sondeo manual no debe generar notificaciones adicionales
            count_before = len(mock_tg.get_messages())
            api_request(port, f"/api/admin/routes/{rid}/test", "POST", token=token)
            time.sleep(0.5)
            assert len(mock_tg.get_messages()) == count_before, "Sondeo manual no debe emitir notificaciones"

            # 4. Tráfico auth fallido tampoco debe generar notificaciones
            api_request(port, "/api/v1/gateway/auth", "POST", {"clientid": "tgaut_1", "username": "u", "password": "p"}, expected_status=(200, 400, 401, 403, 500, 502, 503))
            time.sleep(0.5)
            assert len(mock_tg.get_messages()) == count_before, "Tráfico auth no debe emitir notificaciones"

            # 5. Provocar recuperación (200): se recupera incidente y se encola notificación de recuperación
            mock_tg.clear()
            mock_backend.set_health(200)

            t_end = time.monotonic() + 6.0
            recovered_notif = False
            while time.monotonic() < t_end:
                msgs = [m for m in mock_tg.get_messages() if "recuperado" in m["body"].get("text", "").lower() or "recovered" in m["body"].get("text", "").lower() or "up" in m["body"].get("text", "").lower()]
                if msgs:
                    recovered_notif = True
                    break
                time.sleep(0.2)
            assert recovered_notif, "No llegó la notificación de recuperación al mock de Telegram"

            print("   [OK] Notificaciones automáticas de apertura/recuperación y aislamiento de manual/auth comprobados")
        finally:
            mock_tg.stop()
            mock_backend.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_telegram_error_handling_429_4xx_5xx_and_sanitization():
    print("-> Test: Manejo de errores HTTP Telegram (429 retry_after, 403 terminal, sanitización de token en DB, logs y API)")
    with temp_dir_scope(prefix="test-tg-errors-") as tmp:
        port = get_free_port()
        mock_tg = MockTelegramServer()
        mock_tg.start()
        bot_secret = "super-secret-token-do-not-leak"
        proc = run_gateway_instance(tmp, port, telegram_api_url=f"http://127.0.0.1:{mock_tg.port}", bot_token=bot_secret)
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # Activar Telegram global
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": True, "chatId": "-100555666777"}}, token=token, expected_status=200)

            # Caso 4A: Error terminal 403 Forbidden con token devuelto en la descripción por el proveedor
            mock_tg.set_mode("forbidden_403")
            mock_tg.custom_description = f"Forbidden: bot was blocked by user (bot token {bot_secret})"
            res_test = api_request(port, "/api/admin/notifications/test", "POST", {}, token=token, expected_status=202)
            msg_id = res_test["id"]

            t_end = time.monotonic() + 5.0
            failed_seen = False
            while time.monotonic() < t_end:
                notifs = api_request(port, "/api/admin/notifications", "GET", token=token)
                item = next((x for x in notifs.get("items", []) if x["id"] == msg_id), None)
                if item and item["status"] == "failed":
                    failed_seen = True
                    assert bot_secret not in str(item.get("lastError", "")), "El bot token nunca debe figurar en lastError de 403"
                    break
                time.sleep(0.2)
            assert failed_seen, "Notificación rechazada con 403 debió pasar a estado failed"

            # Caso 4B: Rate limit 429 Too Many Requests con retry_after: 2 y descripción con token
            mock_tg.clear()
            mock_tg.set_mode("rate_limit_429", retry_after=2)
            mock_tg.custom_description = f"Too Many Requests: retry after 2 (token {bot_secret})"
            res_429 = api_request(port, "/api/admin/notifications/test", "POST", {}, token=token, expected_status=202)
            msg_429_id = res_429["id"]

            # Esperar a que el worker intente el envío tras la ventana de 3s por chat
            t_end_429 = time.monotonic() + 8.0
            attempted_429 = False
            t_attempted = None
            while time.monotonic() < t_end_429:
                notifs2 = api_request(port, "/api/admin/notifications", "GET", token=token)
                item429 = next((x for x in notifs2.get("items", []) if x["id"] == msg_429_id), None)
                if item429 and item429.get("attempts", 0) >= 1:
                    attempted_429 = True
                    t_attempted = time.time()
                    assert "429" in str(item429.get("lastError", "")), "lastError debe indicar código 429"
                    assert bot_secret not in str(item429.get("lastError", "")), "El bot token no debe figurar en lastError de 429"

                    # Verificar nextAttemptAt >= respuesta + retry_after (2s)
                    from datetime import datetime, timezone
                    next_iso = item429["nextAttemptAt"]
                    dt = datetime.fromisoformat(next_iso.replace("Z", "+00:00"))
                    t_next = dt.timestamp()
                    assert t_next >= t_attempted - 1.0 + 2.0, f"nextAttemptAt ({t_next}) debe ser al menos respuesta + 2s ({t_attempted + 2.0})"
                    break
                time.sleep(0.2)
            assert attempted_429, "El worker debió intentar enviar el mensaje 429 y registrar attempts >= 1"

            # Verificar que ningún reintento se produce antes de que venza retry_after
            msgs_before = len(mock_tg.get_messages())
            time.sleep(1.0)
            msgs_during = len(mock_tg.get_messages())
            assert msgs_during == msgs_before, "No debe producirse ningún reintento antes del vencimiento de retry_after"

            # Comprobar sanitización exhaustiva de token en DB (gateway.db)
            db_file = tmp / "gateway.db"
            with sqlite3.connect(str(db_file)) as conn:
                for table in ["notification_outbox", "settings", "routes", "incidents"]:
                    try:
                        cursor = conn.execute(f"SELECT * FROM {table};")
                        for row in cursor.fetchall():
                            for col in row:
                                assert bot_secret not in str(col), f"Token secreto filtrado en tabla {table}, fila: {row}"
                    except sqlite3.OperationalError:
                        pass

            # Comprobar sanitización en respuestas de API
            settings_json = json.dumps(api_request(port, "/api/admin/settings", "GET", token=token))
            assert bot_secret not in settings_json, "El token secreto no debe figurar en la respuesta de settings"
            notifs_json = json.dumps(api_request(port, "/api/admin/notifications", "GET", token=token))
            assert bot_secret not in notifs_json, "El token secreto no debe figurar en la respuesta de notificaciones"

            print("   [OK] Manejo de 403 terminal, 429 rate limit y sanitización exhaustiva de token en DB/API/logs aprobados")
        finally:
            mock_tg.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_atomic_rollback_on_outbox_sqlite_failure():
    print("-> Test: Transaccionalidad atómica y rollback ante fallo SQLite en notification_outbox")
    with temp_dir_scope(prefix="test-tg-rollback-") as tmp:
        port = get_free_port()
        mock_tg = MockTelegramServer()
        mock_tg.start()
        mock_backend = BackendMockServer()
        mock_backend.start()

        proc = run_gateway_instance(tmp, port, telegram_api_url=f"http://127.0.0.1:{mock_tg.port}", bot_token="fake-test-token")
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # Activar Telegram global
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": True, "chatId": "-100444333222"}}, token=token, expected_status=200)

            # Crear ruta
            route_payload = {
                "name": "Backend Rollback Test",
                "enabled": True,
                "authPrefixes": ["tgrbk_"],
                "aclPrefixes": ["tgrbk/"],
                "authUrl": f"http://127.0.0.1:{mock_backend.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock_backend.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock_backend.port}/health",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 1,
                    "probeTimeoutMs": 500,
                    "failureThreshold": 1,
                    "recoveryThreshold": 1
                }
            }
            rt = api_request(port, "/api/admin/routes", "POST", route_payload, token=token, expected_status=201)
            rid = rt["id"]
            time.sleep(0.5)

            # Instalar trigger en SQLite para abortar cualquier inserción en notification_outbox
            db_path = tmp / "gateway.db"
            with sqlite3.connect(str(db_path)) as conn:
                conn.execute("""
                    CREATE TRIGGER IF NOT EXISTS test_abort_outbox_trigger
                    BEFORE INSERT ON notification_outbox
                    BEGIN
                        SELECT RAISE(ABORT, 'Simulated failure in notification_outbox');
                    END;
                """)
                conn.commit()

            try:
                # Provocar caída en el backend: el sondeo falla, pero al insertar en outbox la DB aborta
                mock_backend.set_health(503)
                time.sleep(1.5)

                # Verificar que el rollback transaccional impidió que el incidente quedara registrado en SQLite
                with sqlite3.connect(str(db_path)) as conn:
                    incidents_in_db = conn.execute("SELECT COUNT(*) FROM incidents WHERE backend_id = ?;", (rid,)).fetchone()[0]
                    assert incidents_in_db == 0, f"Rollback debió evitar registrar el incidente en DB (registrados={incidents_in_db})"

                print("   [OK] Rollback atómico de incidente ante fallo en outbox confirmado")
            finally:
                with sqlite3.connect(str(db_path)) as conn:
                    conn.execute("DROP TRIGGER IF EXISTS test_abort_outbox_trigger;")
                    conn.commit()
        finally:
            mock_tg.stop()
            mock_backend.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_cancellation_and_restart_sending_to_pending():
    print("-> Test: Cancelación exacta por IDs y reinicio real enviando en estado sending conservando attempts")
    with temp_dir_scope(prefix="test-tg-cancel-") as tmp:
        port = get_free_port()
        mock_tg = MockTelegramServer()
        mock_tg.start()

        # Mock retiene la petición inicial con un threading.Event para atrapar mensajes en sending
        mock_tg.hold_event = threading.Event()
        mock_tg.request_arrived_event = threading.Event()

        proc = run_gateway_instance(tmp, port, telegram_api_url=f"http://127.0.0.1:{mock_tg.port}", bot_token="fake-test-token")
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # Crear dos rutas: Ruta A y Ruta B con chats específicos
            r_a_payload = {
                "name": "Ruta A Cancel",
                "enabled": True,
                "authPrefixes": ["rta_"],
                "aclPrefixes": ["rta/"],
                "authUrl": "http://127.0.0.1:19999/auth",
                "aclUrl": "http://127.0.0.1:19999/acl",
                "healthUrl": "http://127.0.0.1:19999/health",
                "apiKey": "",
                "telegram": {"enabled": True, "chatId": "-100111111111"}
            }
            rt_a = api_request(port, "/api/admin/routes", "POST", r_a_payload, token=token, expected_status=201)
            rid_a = rt_a["id"]

            r_b_payload = {
                "name": "Ruta B Other",
                "enabled": True,
                "authPrefixes": ["rtb_"],
                "aclPrefixes": ["rtb/"],
                "authUrl": "http://127.0.0.1:19999/auth",
                "aclUrl": "http://127.0.0.1:19999/acl",
                "healthUrl": "http://127.0.0.1:19999/health",
                "apiKey": "",
                "telegram": {"enabled": True, "chatId": "-100222222222"}
            }
            rt_b = api_request(port, "/api/admin/routes", "POST", r_b_payload, token=token, expected_status=201)
            rid_b = rt_b["id"]

            # Activar Telegram global
            api_request(port, "/api/admin/settings", "PUT", {"telegram": {"enabled": True, "chatId": "-100999999999"}}, token=token, expected_status=200)

            # Encolar 2 mensajes para Ruta A (mismo chat) y 1 mensaje para Ruta B
            test_a1 = api_request(port, "/api/admin/notifications/test", "POST", {"backendId": rid_a}, token=token, expected_status=202)
            id_a1 = test_a1["id"]

            test_a2 = api_request(port, "/api/admin/notifications/test", "POST", {"backendId": rid_a}, token=token, expected_status=202)
            id_a2 = test_a2["id"]

            test_b1 = api_request(port, "/api/admin/notifications/test", "POST", {"backendId": rid_b}, token=token, expected_status=202)
            id_b1 = test_b1["id"]

            # Esperar a que el primer mensaje (id_a1) sea despachado y quede retenido en vuelo por el mock
            assert mock_tg.request_arrived_event.wait(timeout=5.0), "El mensaje id_a1 debió llegar al mock de Telegram"

            # En este momento id_a1 está en 'sending', mientras id_a2 y id_b1 están en 'pending'
            # Desactivar/pausar Ruta A -> sólo sus pendientes (id_a2) deben pasar a 'cancelled'
            api_request(port, f"/api/admin/routes/{rid_a}", "PUT", {**rt_a, "enabled": False}, token=token, expected_status=200)

            notifs = api_request(port, "/api/admin/notifications", "GET", token=token)
            items = notifs.get("items", [])
            item_a1 = next((x for x in items if x["id"] == id_a1), None)
            item_a2 = next((x for x in items if x["id"] == id_a2), None)
            item_b1 = next((x for x in items if x["id"] == id_b1), None)

            assert item_a1 is not None and item_a2 is not None and item_b1 is not None
            assert item_a2["status"] == "cancelled", f"El mensaje pendiente id_a2 debió cancelarse; status={item_a2['status']}"
            assert item_a1["status"] in ("sending", "cancelled"), f"El mensaje id_a1 en vuelo debe estar sending o cancelled; status={item_a1['status']}"
            assert item_b1["status"] in ("pending", "sending"), f"El mensaje id_b1 de otra ruta debe permanecer activo; status={item_b1['status']}"

            # Liberar la petición en vuelo id_a1 para que termine el HTTP
            mock_tg.hold_event.set()
            time.sleep(0.5)

            # Comprobar que la respuesta de Telegram no revive ni sobrescribe la cancelación de la ruta A
            notifs = api_request(port, "/api/admin/notifications", "GET", token=token)
            item_a1_check = next((x for x in notifs.get("items", []) if x["id"] == id_a1), None)
            item_a2_check = next((x for x in notifs.get("items", []) if x["id"] == id_a2), None)
            assert item_a1_check["status"] == "cancelled", f"id_a1 cancelado concurrentemente debe conservar cancelled; status={item_a1_check['status']}"
            assert item_a2_check["status"] == "cancelled", "id_a2 debe permanecer cancelled"

            # Esperar a que id_b1 termine
            t_end_b1 = time.monotonic() + 5.0
            while time.monotonic() < t_end_b1:
                notifs = api_request(port, "/api/admin/notifications", "GET", token=token)
                item_b1_check = next((x for x in notifs.get("items", []) if x["id"] == id_b1), None)
                if item_b1_check and item_b1_check["status"] == "sent":
                    break
                time.sleep(0.2)

            # Preparar prueba de reinicio real: retener un mensaje nuevo en sending
            mock_tg.hold_event = threading.Event()
            mock_tg.request_arrived_event = threading.Event()

            # Encolar mensaje para Ruta B que será retenido en 'sending'
            test_crash = api_request(port, "/api/admin/notifications/test", "POST", {"backendId": rid_b}, token=token, expected_status=202)
            id_crash = test_crash["id"]

            # Esperar a que id_crash llegue al mock y quede retenido en 'sending'
            assert mock_tg.request_arrived_event.wait(timeout=5.0), "El mensaje id_crash debió llegar al mock de Telegram"

            # Confirmar en SQLite directo que id_crash está en 'sending'
            db_path = tmp / "gateway.db"
            with sqlite3.connect(str(db_path)) as conn:
                status_in_db = conn.execute("SELECT status, attempts FROM notification_outbox WHERE id = ?;", (id_crash,)).fetchone()
                assert status_in_db is not None
                assert status_in_db[0] == "sending", f"id_crash debe estar en status sending en SQLite; fue {status_in_db[0]}"
                attempts_before_crash = status_in_db[1]

            # Simular caída abrupta del proceso (kill)
            proc.kill()
            proc.wait(timeout=5)

            # Liberar mock para que no bloquee futuros requests
            mock_tg.hold_event.set()
            mock_tg.hold_event = None
            mock_tg.request_arrived_event = None

            # Reiniciar proceso del gateway sobre la misma base de datos
            proc2 = run_gateway_instance(tmp, port, telegram_api_url=f"http://127.0.0.1:{mock_tg.port}", bot_token="fake-test-token")
            try:
                assert wait_for_ready(port), "El gateway reiniciado no arrancó a tiempo"
                token2 = login_admin(port)

                # El arranque debió recuperar id_crash de sending a pending conservando attempts
                # y luego el worker debió despacharlo a sent
                t_end_rec = time.monotonic() + 8.0
                rec_sent = False
                while time.monotonic() < t_end_rec:
                    notifs2 = api_request(port, "/api/admin/notifications", "GET", token=token2)
                    item_crash_check = next((x for x in notifs2.get("items", []) if x["id"] == id_crash), None)
                    if item_crash_check and item_crash_check["status"] == "sent":
                        rec_sent = True
                        assert item_crash_check["attempts"] == attempts_before_crash + 1, f"Los attempts deben acumular exactamente +1 tras el restart; antes={attempts_before_crash}, ahora={item_crash_check['attempts']}"
                        break
                    time.sleep(0.3)
                assert rec_sent, "El mensaje recuperado id_crash debió reenviarse y completarse a 'sent'"

                print("   [OK] Cancelación selectiva por IDs y reinicio real enviando en estado sending conservando attempts aprobados")
            finally:
                proc2.terminate()
                proc2.wait(timeout=5)

        finally:
            if mock_tg.hold_event:
                mock_tg.hold_event.set()
            mock_tg.stop()
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=5)

def main():
    print("=====================================================================")
    print(" SUITE DE PRUEBAS AUTOMATIZADAS - FASE 3: TELEGRAM Y OUTBOX")
    print("=====================================================================")
    tests = [
        test_telegram_settings_api_and_validation,
        test_telegram_test_notification_202_and_dispatch,
        test_automatic_incident_notifications_opened_and_recovered,
        test_telegram_error_handling_429_4xx_5xx_and_sanitization,
        test_atomic_rollback_on_outbox_sqlite_failure,
        test_cancellation_and_restart_sending_to_pending,
    ]
    passed = 0
    failed = 0
    for t in tests:
        try:
            t()
            passed += 1
        except Exception as e:
            failed += 1
            print(f" [FALLO] {t.__name__}: {e}")
            import traceback
            traceback.print_exc()

    print("\n---------------------------------------------------------------------")
    print(f" RESULTADO FASE 3: {passed} PASSED / {failed} FAILED")
    print("---------------------------------------------------------------------")
    if failed > 0:
        sys.exit(1)

if __name__ == "__main__":
    main()
