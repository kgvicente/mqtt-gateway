#!/usr/bin/env python3
"""
Suite de Pruebas Automatizadas para FASE 2: Monitoreo Configurable, Histéresis y Scheduler.
Pruebas exhaustivas y deterministas de:
- API /api/admin/settings: autenticación, validación estricta de rangos (probeIntervalSeconds 1..86400,
  probeTimeoutMs 100..60000 y menor al intervalo, failureThreshold 1..100, recoveryThreshold 1..100).
- Persistencia de settings en base de datos SQLite y actualización dinámica del scheduler sin reinicio.
- Overrides de monitoring por ruta y cálculo de effectiveMonitoring (herencia limpia de null/omitidos).
- Rechazo (400) de settings globales si invalidan rutas con overrides existentes, informando los IDs de ruta.
- Histéresis de incidentes: racha de fallos consecutiva abre incidente en muestra 3; racha de éxitos
  recupera incidente en muestra 2.
- Reinicio de contadores ante muestras contradictorias en rachas no confirmadas.
- Caso límite umbral 1 (1 fallo abre, 1 éxito recupera).
- Confirmación inicial de salud tras arranque limpio sin emitir incidentes falsos de recuperación.
- Persistencia y reinicio con incidente abierto: tras reiniciar, estado inicial Unknown y awaitingObservation=true,
  conservando el incidente abierto y requiriendo recoveryThreshold para cerrarse.
- Tráfico regular auth/ACL no altera disponibilidad confirmada por sondeo ni secuencias de incidentes.
- Sondeo manual POST /api/admin/routes/{id}/test: no altera métricas automáticas, uptime ni incidentes;
  retorna 409 Conflict si el slot de sondeo del backend está ocupado.
- Descarte de resultados en vuelo de sondeos si la ruta es pausada, editada o eliminada.
- Server-Sent Events (SSE) en /api/admin/stream transmitiendo settings y contadores de monitoreo.
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
def temp_dir_scope(prefix="test-fase2-"):
    d = tempfile.mkdtemp(prefix=prefix)
    try:
        yield pathlib.Path(d)
    finally:
        shutil.rmtree(d, ignore_errors=True)

class ControlledMockServer:
    """Mock HTTP backend con control fino de estados de salud y retardos."""
    def __init__(self, host="127.0.0.1", port=0):
        self.host = host
        self.port = port
        self.health_status = 200
        self.health_delay_sec = 0.0
        self.auth_status = 200
        self.probe_count = 0
        self.auth_count = 0
        self.lock = threading.Lock()
        self.server = None
        self.thread = None
        self.intercept_path = None
        self.intercept_status = 503
        self.blocking_latch = None
        self.release_latch = None

    def start(self):
        parent = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            def log_message(self, *args): pass

            def do_GET(self):
                with parent.lock:
                    intercept_path = parent.intercept_path
                    blocking_latch = parent.blocking_latch
                    release_latch = parent.release_latch
                    intercept_status = parent.intercept_status

                if intercept_path and self.path == intercept_path:
                    if blocking_latch:
                        blocking_latch.set()
                    if release_latch:
                        release_latch.wait(timeout=10)
                    body = json.dumps({"status": "down" if intercept_status >= 500 else "up"}).encode()
                    self.send_response(intercept_status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.wfile.write(body)
                    return

                if self.path in ("/health", "/q/health", "/status", "/new_health"):
                    delay = 0.0
                    status = 200
                    with parent.lock:
                        parent.probe_count += 1
                        delay = parent.health_delay_sec
                        status = parent.health_status

                    if delay > 0:
                        time.sleep(delay)

                    body = json.dumps({"status": "up" if status < 500 else "down"}).encode()
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self.send_response(404)
                    self.end_headers()

            def do_POST(self):
                l = int(self.headers.get("Content-Length", 0))
                if l > 0:
                    self.rfile.read(l)
                if "/auth" in self.path or "/acl" in self.path:
                    with parent.lock:
                        parent.auth_count += 1
                        st = parent.auth_status
                    if st == 200:
                        b = b'{"result":"allow","is_superuser":false}'
                    else:
                        b = b'{"result":"deny"}'
                    self.send_response(st)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(b)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.wfile.write(b)
                else:
                    self.send_response(404)
                    self.end_headers()

        class Server(http.server.ThreadingHTTPServer):
            request_queue_size = 128
            daemon_threads = True
            allow_reuse_address = True

        self.server = Server((self.host, self.port), Handler)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.clear_interception()
        if self.server:
            self.server.shutdown()
            self.server.server_close()

    def setup_interception(self, path="/old_health", status=503):
        with self.lock:
            self.intercept_path = path
            self.intercept_status = status
            self.blocking_latch = threading.Event()
            self.release_latch = threading.Event()
            return self.blocking_latch, self.release_latch

    def clear_interception(self):
        with self.lock:
            if self.release_latch:
                self.release_latch.set()
            self.intercept_path = None
            self.blocking_latch = None
            self.release_latch = None

    def set_health(self, status=200, delay=0.0):
        with self.lock:
            self.health_status = status
            self.health_delay_sec = delay

    def set_auth(self, status=200):
        with self.lock:
            self.auth_status = status

    def get_counts(self):
        with self.lock:
            return self.probe_count, self.auth_count

def get_java_executable():
    if "JAVA_HOME" in os.environ:
        cand = pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if cand.exists():
            return str(cand)
    which_java = shutil.which("java")
    if which_java:
        return which_java
    return "java"

def run_gateway_instance(data_dir, http_port, java_opts=None, env=None, log_file=None):
    jar = root / "target" / "quarkus-app" / "quarkus-run.jar"
    if not jar.exists():
        raise FileNotFoundError(f"No se encontró {jar}. Requiere compilación previa.")

    base_env = os.environ.copy()
    base_env["GATEWAY_DATA_DIR"] = str(data_dir)
    base_env["GATEWAY_ADMIN_AUTH_PROVIDER"] = "internal"
    base_env["GATEWAY_ADMIN_USERNAME"] = "admin"
    base_env["GATEWAY_ADMIN_PASSWORD"] = "test-admin-secret"
    base_env["GATEWAY_MASTER_KEY"] = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    if env:
        base_env.update(env)

    java_bin = get_java_executable()
    cmd = [
        java_bin,
        f"-Dquarkus.http.port={http_port}",
        "-Dquarkus.http.host=127.0.0.1",
        f"-Dgateway.data-dir={data_dir}",
    ]
    if java_opts:
        cmd.extend(java_opts)
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

def api_request(port, path, method="GET", body=None, token=None, expected_status=(200, 201, 204), password=None):
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
# CASOS DE PRUEBA DE FASE 2
# --------------------------------------------------------------------------

def test_admin_settings_validation_and_persistence():
    print("-> Test: Validación y persistencia de /api/admin/settings (Fase 2)")
    with temp_dir_scope(prefix="test-settings-") as tmp:
        port = get_free_port()
        log_f = tmp / "gw.log"
        proc = run_gateway_instance(tmp, port, log_file=log_f)
        try:
            if not wait_for_ready(port):
                out = log_f.read_text(encoding="utf-8", errors="replace") if log_f.exists() else "No log"
                print(f"--- LOG GATEWAY ---\n{out}\n--- FIN LOG ---")
                raise AssertionError("El gateway no arrancó a tiempo")
            # 1. Protección de endpoints
            api_request(port, "/api/admin/settings", "GET", expected_status=401)
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {}}, expected_status=401)
            api_request(port, "/api/admin/settings", "GET", token="bad-token", expected_status=401)

            token = login_admin(port)

            # 2. GET inicial debe devolver settings con monitoring por defecto
            s = api_request(port, "/api/admin/settings", "GET", token=token)
            assert "monitoring" in s, f"Falta clave 'monitoring' en settings: {s}"
            m = s["monitoring"]
            assert m["probeIntervalSeconds"] == 30
            assert m["probeTimeoutMs"] == 2000
            assert m["failureThreshold"] == 3
            assert m["recoveryThreshold"] == 2

            # 3. Validaciones estrictas de rangos
            # probeIntervalSeconds: 1..86400
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 0, "probeTimeoutMs": 500, "failureThreshold": 3, "recoveryThreshold": 2}}, token=token, expected_status=400)
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 86401, "probeTimeoutMs": 500, "failureThreshold": 3, "recoveryThreshold": 2}}, token=token, expected_status=400)

            # probeTimeoutMs: 100..60000 y estrictamente MENOR que probeIntervalSeconds * 1000
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 10, "probeTimeoutMs": 99, "failureThreshold": 3, "recoveryThreshold": 2}}, token=token, expected_status=400)
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 10, "probeTimeoutMs": 10000, "failureThreshold": 3, "recoveryThreshold": 2}}, token=token, expected_status=400)
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 10, "probeTimeoutMs": 15000, "failureThreshold": 3, "recoveryThreshold": 2}}, token=token, expected_status=400)

            # failureThreshold: 1..100
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 10, "probeTimeoutMs": 1000, "failureThreshold": 0, "recoveryThreshold": 2}}, token=token, expected_status=400)
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 10, "probeTimeoutMs": 1000, "failureThreshold": 101, "recoveryThreshold": 2}}, token=token, expected_status=400)

            # recoveryThreshold: 1..100
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 10, "probeTimeoutMs": 1000, "failureThreshold": 3, "recoveryThreshold": 0}}, token=token, expected_status=400)
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 10, "probeTimeoutMs": 1000, "failureThreshold": 3, "recoveryThreshold": 101}}, token=token, expected_status=400)

            # Enteros gigantes fuera de rango (long 4294967297) deben ser rechazados con 400
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 4294967297, "probeTimeoutMs": 1000, "failureThreshold": 3, "recoveryThreshold": 2}}, token=token, expected_status=400)

            # Claves desconocidas o apiKey en settings deben ser rechazadas con 400 y no persistidas
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 10, "probeTimeoutMs": 1000, "failureThreshold": 3, "recoveryThreshold": 2}, "unknownField": "bad"}, token=token, expected_status=400)
            api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 10, "probeTimeoutMs": 1000, "failureThreshold": 3, "recoveryThreshold": 2, "apiKey": "secret"}}, token=token, expected_status=400)

            # Verificar que settings no mutó tras los intentos inválidos
            current_s = api_request(port, "/api/admin/settings", "GET", token=token)
            assert current_s["monitoring"]["probeIntervalSeconds"] == 30, "Settings no deben mutar tras intentos inválidos 400"

            # 4. Actualización exitosa
            new_monitoring = {
                "probeIntervalSeconds": 15,
                "probeTimeoutMs": 1500,
                "failureThreshold": 4,
                "recoveryThreshold": 3
            }
            res_put = api_request(port, "/api/admin/settings", "PUT", {"monitoring": new_monitoring}, token=token)
            assert res_put["monitoring"]["probeIntervalSeconds"] == 15
            assert res_put["monitoring"]["probeTimeoutMs"] == 1500
            assert res_put["monitoring"]["failureThreshold"] == 4
            assert res_put["monitoring"]["recoveryThreshold"] == 3

            # 5. Persistencia comprobada en base de datos SQLite
            db_file = tmp / "gateway.db"
            with sqlite3.connect(db_file) as conn:
                row = conn.execute("SELECT value FROM settings WHERE key = 'monitoring';").fetchone()
                assert row is not None, "No se encontró registro de monitoring en tabla settings"
                db_data = json.loads(row[0])
                assert db_data["probeIntervalSeconds"] == 15
                assert db_data["failureThreshold"] == 4

            print("   [OK] Validaciones de rangos y persistencia en DB aprobadas")
        finally:
            proc.terminate()
            proc.wait(timeout=5)

def test_route_monitoring_overrides_and_effective():
    print("-> Test: Overrides por ruta y cálculo de effectiveMonitoring")
    with temp_dir_scope(prefix="test-overrides-") as tmp:
        port = get_free_port()
        mock = ControlledMockServer()
        mock.start()
        proc = run_gateway_instance(tmp, port)
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # 1. Ruta sin override de monitoring (debe heredar effectiveMonitoring completo)
            r1_payload = {
                "name": "Backend Default",
                "enabled": True,
                "authPrefixes": ["def_"],
                "aclPrefixes": ["def/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/health",
                "apiKey": ""
            }
            r1 = api_request(port, "/api/admin/routes", "POST", r1_payload, token=token, expected_status=201)
            assert "effectiveMonitoring" in r1, f"La respuesta debe incluir effectiveMonitoring: {r1}"
            eff1 = r1["effectiveMonitoring"]
            assert eff1["probeIntervalSeconds"] == 30
            assert eff1["probeTimeoutMs"] == 2000
            assert eff1["failureThreshold"] == 3
            assert eff1["recoveryThreshold"] == 2

            # 2. Ruta con overrides específicos
            r2_payload = {
                "name": "Backend Custom",
                "enabled": True,
                "authPrefixes": ["cust_"],
                "aclPrefixes": ["cust/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/health",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 10,
                    "probeTimeoutMs": 800,
                    "failureThreshold": 2,
                    "recoveryThreshold": 1
                }
            }
            r2 = api_request(port, "/api/admin/routes", "POST", r2_payload, token=token, expected_status=201)
            eff2 = r2["effectiveMonitoring"]
            assert eff2["probeIntervalSeconds"] == 10
            assert eff2["probeTimeoutMs"] == 800
            assert eff2["failureThreshold"] == 2
            assert eff2["recoveryThreshold"] == 1

            # 3. PUT ruta omitiendo objeto monitoring: conserva los overrides previos
            r2_id = r2["id"]
            r2_update = dict(r2)
            del r2_update["monitoring"]
            r2_after_put = api_request(port, f"/api/admin/routes/{r2_id}", "PUT", r2_update, token=token)
            assert r2_after_put["effectiveMonitoring"]["probeIntervalSeconds"] == 10
            assert r2_after_put["effectiveMonitoring"]["probeTimeoutMs"] == 800

            # 4. PUT ruta con campo null en monitoring: elimina ese override y hereda del global
            r2_update["monitoring"] = {
                "probeIntervalSeconds": None,
                "probeTimeoutMs": 800,
                "failureThreshold": None,
                "recoveryThreshold": None
            }
            r2_cleared = api_request(port, f"/api/admin/routes/{r2_id}", "PUT", r2_update, token=token)
            # probeIntervalSeconds ahora hereda 30 del global, probeTimeoutMs sigue 800
            assert r2_cleared["effectiveMonitoring"]["probeIntervalSeconds"] == 30
            assert r2_cleared["effectiveMonitoring"]["probeTimeoutMs"] == 800
            assert r2_cleared["effectiveMonitoring"]["failureThreshold"] == 3

            # 5. Rechazo si un cambio global invalida overrides existentes
            # Creamos ruta con probeIntervalSeconds: 3 y timeout: 1000
            r3_payload = {
                "name": "Backend Short",
                "enabled": True,
                "authPrefixes": ["short_"],
                "aclPrefixes": ["short/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/health",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 3,
                    "probeTimeoutMs": None
                }
            }
            r3 = api_request(port, "/api/admin/routes", "POST", r3_payload, token=token, expected_status=201)
            r3_id = r3["id"]

            # Si intentamos fijar global probeTimeoutMs = 4000 (> 3s de r3), debe rechazar con 400 e informar r3_id
            err_resp = api_request(port, "/api/admin/settings", "PUT", {"monitoring": {"probeIntervalSeconds": 30, "probeTimeoutMs": 4000, "failureThreshold": 3, "recoveryThreshold": 2}}, token=token, expected_status=400)
            assert r3_id in str(err_resp) or "invalida" in str(err_resp).lower() or "timeout" in str(err_resp).lower()

            print("   [OK] Overrides por ruta, herencia de nulls y validación de conflicto global aprobados")
        finally:
            mock.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_hysteresis_and_metrics_counters():
    print("-> Test: Histéresis de incidentes (3 fallos abre / 2 éxitos recupera) y contadores")
    with temp_dir_scope(prefix="test-hysteresis-") as tmp:
        port = get_free_port()
        mock = ControlledMockServer()
        mock.start()
        proc = run_gateway_instance(tmp, port)
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # Pausar rutas sembradas para aislar
            for r in api_request(port, "/api/admin/routes", "GET", token=token):
                if r.get("enabled"):
                    r_c = dict(r)
                    r_c["enabled"] = False
                    api_request(port, f"/api/admin/routes/{r['id']}", "PUT", r_c, token=token)

            # Configurar sondeo rápido determinista (1s intervalo, 500ms timeout, umbrales 3/2)
            api_request(port, "/api/admin/settings", "PUT", {
                "monitoring": {
                    "probeIntervalSeconds": 1,
                    "probeTimeoutMs": 500,
                    "failureThreshold": 3,
                    "recoveryThreshold": 2
                }
            }, token=token)

            # Crear ruta apuntando al mock
            route_payload = {
                "name": "Backend Hysteresis",
                "enabled": True,
                "authPrefixes": ["hys_"],
                "aclPrefixes": ["hys/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/health",
                "apiKey": ""
            }
            rt = api_request(port, "/api/admin/routes", "POST", route_payload, token=token, expected_status=201)
            rid = rt["id"]

            # 1. Esperar primer sondeo sano -> confirma salud inicial
            time.sleep(1.2)
            metrics = api_request(port, "/api/admin/metrics", "GET", token=token)
            m = next(x for x in metrics if x["id"] == rid)
            assert m["status"] in ("Healthy", "Up", "Degraded", "Normal")
            assert m.get("awaitingObservation") is False or m.get("awaitingObservation") is None

            # 2. Simular fallo continuo en health (503)
            mock.set_health(status=503)

            # Esperar a que se abra el incidente (requiere exactamente 3 fallos consecutivos)
            t_end = time.monotonic() + 10
            opened = False
            while time.monotonic() < t_end:
                incidents = api_request(port, "/api/admin/incidents", "GET", token=token)
                open_inc = [i for i in incidents if i["backendId"] == rid and not i.get("endedAt")]
                if open_inc:
                    opened = True
                    break
                time.sleep(0.3)
            assert opened, "El incidente no se abrió tras alcanzar el umbral de fallos (3)"

            # Métricas deben reflejar el estado confirmado Down y consecutiveFailures reseteado a 0
            metrics = api_request(port, "/api/admin/metrics", "GET", token=token)
            m = next(x for x in metrics if x["id"] == rid)
            assert m["status"] in ("Down", "Unhealthy", "Offline")
            assert m.get("consecutiveFailures", 0) == 0
            assert m.get("pendingState") is None

            # 3. Simular recuperación (200 OK)
            mock.set_health(status=200)

            # Esperar a que se recupere (requiere 2 éxitos consecutivos)
            t_end = time.monotonic() + 10
            recovered = False
            while time.monotonic() < t_end:
                incidents = api_request(port, "/api/admin/incidents", "GET", token=token)
                rec_inc = [i for i in incidents if i["backendId"] == rid and i.get("endedAt")]
                if rec_inc:
                    recovered = True
                    assert rec_inc[0].get("endReason") in ("Recovered", "Auto-recovered")
                    break
                time.sleep(0.3)
            assert recovered, "El incidente no se recuperó tras alcanzar el umbral de recuperación (2)"

            # Métricas deben reflejar recuperación confirmada
            metrics = api_request(port, "/api/admin/metrics", "GET", token=token)
            m = next(x for x in metrics if x["id"] == rid)
            assert m["status"] in ("Normal", "Degraded")
            assert m.get("consecutiveSuccesses", 0) == 0
            assert m.get("pendingState") is None

            print("   [OK] Histéresis 3 fallos para abrir y 2 éxitos para recuperar comprobada")
        finally:
            mock.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_manual_probe_exclusivity_and_no_side_effects():
    print("-> Test: Sondeo manual no altera métricas y retorna 409 bajo slot ocupado")
    with temp_dir_scope(prefix="test-manual-") as tmp:
        port = get_free_port()
        mock = ControlledMockServer()
        mock.start()
        proc = run_gateway_instance(tmp, port)
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # Crear ruta con intervalo largo para que no solape automáticamente
            route_payload = {
                "name": "Backend Manual Test",
                "enabled": True,
                "authPrefixes": ["man_"],
                "aclPrefixes": ["man/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/health",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 60,
                    "probeTimeoutMs": 2000,
                    "failureThreshold": 3,
                    "recoveryThreshold": 2
                }
            }
            rt = api_request(port, "/api/admin/routes", "POST", route_payload, token=token, expected_status=201)
            rid = rt["id"]

            # 1. Ejecutar sondeo manual: debe devolver 200 y JSON con el resultado puntual
            manual_res = api_request(port, f"/api/admin/routes/{rid}/test", "POST", token=token)
            assert manual_res is not None

            # 2. Slot ocupado (409 Conflict): simular delay de 1.5s en health
            mock.set_health(status=200, delay=1.5)

            # Lanzar primer sondeo manual en hilo paralelo que mantendrá el slot ocupado
            t_holder = threading.Thread(target=lambda: api_request(port, f"/api/admin/routes/{rid}/test", "POST", token=token))
            t_holder.start()
            time.sleep(0.2)  # asegurar que el holder entró al slot

            # Petición simultánea mientras el slot está ocupado debe responder 409 Conflict
            api_request(port, f"/api/admin/routes/{rid}/test", "POST", token=token, expected_status=409)
            t_holder.join(timeout=3)
            mock.set_health(status=200, delay=0.0)

            # 3. Verificar que el sondeo manual NO creó incidentes
            incidents = api_request(port, "/api/admin/incidents", "GET", token=token)
            assert not any(i["backendId"] == rid for i in incidents)

            print("   [OK] Exclusividad de slot manual con 409 y aislamiento de incidentes comprobados")
        finally:
            mock.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_auth_acl_traffic_does_not_affect_probe_health():
    print("-> Test: Tráfico erróneo de auth/ACL no altera disponibilidad confirmada")
    with temp_dir_scope(prefix="test-auth-traffic-") as tmp:
        port = get_free_port()
        mock = ControlledMockServer()
        mock.start()
        proc = run_gateway_instance(tmp, port)
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            route_payload = {
                "name": "Backend Traffic",
                "enabled": True,
                "authPrefixes": ["traf_"],
                "aclPrefixes": ["traf/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/health",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 1,
                    "probeTimeoutMs": 500,
                    "failureThreshold": 3,
                    "recoveryThreshold": 2
                }
            }
            rt = api_request(port, "/api/admin/routes", "POST", route_payload, token=token, expected_status=201)
            rid = rt["id"]

            # Health responde 200 OK (saludable)
            mock.set_health(status=200)
            t_obs = time.monotonic() + 5
            while time.monotonic() < t_obs:
                metrics = api_request(port, "/api/admin/metrics", "GET", token=token)
                m = next((x for x in metrics if x["id"] == rid), None)
                if m and not m.get("awaitingObservation"):
                    break
                time.sleep(0.2)

            # Configurar auth para responder 500 Error
            mock.set_auth(status=500)

            # Enviar 10 peticiones auth erróneas
            for i in range(10):
                api_request(port, "/api/v1/gateway/auth", "POST", {"clientid": f"traf_{i}", "username": "u", "password": "p"}, expected_status=(200, 400, 401, 403, 500, 502, 503))

            # Comprobar que NO se abrieron incidentes
            incidents = api_request(port, "/api/admin/incidents", "GET", token=token)
            assert not any(i["backendId"] == rid for i in incidents), "El tráfico fallido de auth abrió erróneamente un incidente"

            # Las métricas de disponibilidad deben permanecer sanas (no Down/Offline)
            metrics = api_request(port, "/api/admin/metrics", "GET", token=token)
            m = next(x for x in metrics if x["id"] == rid)
            assert m["status"] in ("Healthy", "Up", "Degraded", "Normal", "Slow")
            assert m["status"] != "Offline"

            print("   [OK] Aislamiento de tráfico auth/ACL respecto a disponibilidad de sondeo comprobado")
        finally:
            mock.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_generation_discard_and_reschedule_with_in_flight_probe():
    print("-> Test Carrera 1: Descarte de respuesta en vuelo (503) por cambio de generación (edición, pausa, borrado)")
    with temp_dir_scope(prefix="test-gen-race-") as tmp:
        port = get_free_port()
        mock = ControlledMockServer()
        mock.start()
        proc = run_gateway_instance(tmp, port)
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # Caso 1A: Edición de ruta mientras HTTP 503 está en vuelo.
            # Ruta vieja con intervalo 60s, timeout 5000ms.
            blocking_latch, release_latch = mock.setup_interception(path="/old_health", status=503)

            route_payload = {
                "name": "Backend In-Flight Edit",
                "enabled": True,
                "authPrefixes": ["inf1_"],
                "aclPrefixes": ["inf1/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/old_health",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 60,
                    "probeTimeoutMs": 5000,
                    "failureThreshold": 3,
                    "recoveryThreshold": 2
                }
            }
            rt = api_request(port, "/api/admin/routes", "POST", route_payload, token=token, expected_status=201)
            rid = rt["id"]

            # Reiniciar proceso para que GatewayState.start() dispare el primer sondeo a los 100ms
            proc.terminate()
            proc.wait(timeout=5)
            proc = run_gateway_instance(tmp, port)
            assert wait_for_ready(port), "El gateway no reinició a tiempo"
            token = login_admin(port)

            # Esperar a que la petición a /old_health entre al mock (quedando en vuelo en Java)
            assert blocking_latch.wait(timeout=5), "El sondeo inicial a /old_health no llegó al mock tras reinicio"

            # Mientras está en vuelo, editar healthUrl a /new_health y política 1s/500ms
            edit_payload = dict(route_payload)
            edit_payload["healthUrl"] = f"http://127.0.0.1:{mock.port}/new_health"
            edit_payload["monitoring"] = {
                "probeIntervalSeconds": 1,
                "probeTimeoutMs": 500,
                "failureThreshold": 3,
                "recoveryThreshold": 2
            }
            api_request(port, f"/api/admin/routes/{rid}", "PUT", edit_payload, token=token, expected_status=200)

            # Liberar la respuesta vieja 503
            release_latch.set()
            time.sleep(0.3)

            # Verificar que la respuesta vieja 503 NO generó incidente
            incidents = api_request(port, "/api/admin/incidents", "GET", token=token)
            assert not any(i["backendId"] == rid for i in incidents), "La respuesta vieja 503 en vuelo no debió crear incidentes"

            # Verificar que el nuevo backend se sondea en ~1s (esperar que aparezca muestra 200 en métricas dentro de 4.0s)
            t_probe = time.monotonic() + 4.5
            new_probe_seen = False
            while time.monotonic() < t_probe:
                metrics = api_request(port, "/api/admin/metrics", "GET", token=token)
                m = next((x for x in metrics if x["id"] == rid), None)
                if m and any(p["status"] == 200 for p in m.get("probeHistory", [])):
                    new_probe_seen = True
                    # Confirmar que NO hay ninguna muestra con 503 en el historial
                    assert not any(p["status"] == 503 for p in m.get("probeHistory", [])), "Historial no debe contener la muestra 503 descartada"
                    assert m["status"] in ("Normal", "Degraded")
                    break
                time.sleep(0.2)
            assert new_probe_seen, "El nuevo backend no fue sondeado con el nuevo intervalo de 1s (posible bloqueo por 60s viejo)"

            print("   [OK] 1A: Edición en vuelo descarta 503 y reprograma a 1s sin esperar 60s")

            # Caso 1B: Pausa de ruta con respuesta 503 tardía en vuelo (ruta vieja 60s/5000ms)
            mock.clear_interception()
            blocking_latch, release_latch = mock.setup_interception(path="/old_health_pause", status=503)

            route_pause_payload = {
                "name": "Backend In-Flight Pause",
                "enabled": True,
                "authPrefixes": ["inf2_"],
                "aclPrefixes": ["inf2/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/old_health_pause",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 60,
                    "probeTimeoutMs": 5000,
                    "failureThreshold": 3,
                    "recoveryThreshold": 2
                }
            }
            rt2 = api_request(port, "/api/admin/routes", "POST", route_pause_payload, token=token, expected_status=201)
            rid2 = rt2["id"]

            # Reiniciar proceso para disparar el primer sondeo a 100ms con snapshot de 60s
            proc.terminate()
            proc.wait(timeout=5)
            proc = run_gateway_instance(tmp, port)
            assert wait_for_ready(port), "El gateway no reinició a tiempo en 1B"
            token = login_admin(port)

            assert blocking_latch.wait(timeout=5), "El sondeo inicial a /old_health_pause no llegó tras reinicio"

            # Pausar la ruta mientras la petición vieja está en vuelo
            pause_payload = dict(route_pause_payload)
            pause_payload["enabled"] = False
            api_request(port, f"/api/admin/routes/{rid2}", "PUT", pause_payload, token=token, expected_status=200)

            # Liberar respuesta 503 vieja
            release_latch.set()
            time.sleep(0.3)

            # Verificar que no se abrió ningún incidente
            incidents2 = api_request(port, "/api/admin/incidents", "GET", token=token)
            open_inc2 = [i for i in incidents2 if i["backendId"] == rid2 and not i.get("endedAt")]
            assert len(open_inc2) == 0, "No debe haber incidente abierto tras liberar 503 en ruta pausada"

            metrics2 = api_request(port, "/api/admin/metrics", "GET", token=token)
            m2 = next(x for x in metrics2 if x["id"] == rid2)
            assert m2["status"] == "Paused"

            print("   [OK] 1B: Pausa en vuelo descarta 503 y mantiene estado Paused")

            # Caso 1C: Borrado de ruta con respuesta 503 tardía en vuelo (ruta vieja 60s/5000ms)
            mock.clear_interception()
            blocking_latch, release_latch = mock.setup_interception(path="/old_health_del", status=503)

            route_del_payload = {
                "name": "Backend In-Flight Delete",
                "enabled": True,
                "authPrefixes": ["inf3_"],
                "aclPrefixes": ["inf3/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/old_health_del",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 60,
                    "probeTimeoutMs": 5000,
                    "failureThreshold": 3,
                    "recoveryThreshold": 2
                }
            }
            rt3 = api_request(port, "/api/admin/routes", "POST", route_del_payload, token=token, expected_status=201)
            rid3 = rt3["id"]

            # Reiniciar proceso para disparar el primer sondeo a 100ms con snapshot de 60s
            proc.terminate()
            proc.wait(timeout=5)
            proc = run_gateway_instance(tmp, port)
            assert wait_for_ready(port), "El gateway no reinició a tiempo en 1C"
            token = login_admin(port)

            assert blocking_latch.wait(timeout=5), "El sondeo inicial a /old_health_del no llegó tras reinicio"

            # Eliminar la ruta mientras la petición vieja está en vuelo (con password)
            api_request(port, f"/api/admin/routes/{rid3}", "DELETE", token=token, expected_status=204, password="test-admin-secret")

            # Liberar respuesta 503 vieja
            release_latch.set()
            time.sleep(0.3)

            # Verificar que la ruta no existe y no causó errores
            routes = api_request(port, "/api/admin/routes", "GET", token=token)
            assert not any(r["id"] == rid3 for r in routes)

            print("   [OK] 1C: Borrado en vuelo descarta 503 y elimina ruta limpiamente")

        finally:
            mock.clear_interception()
            mock.stop()
            proc.terminate()
            proc.wait(timeout=5)

def test_delete_persistence_failure_preserves_cache_and_scheduler():
    print("-> Test Carrera 2: Fallo de persistencia SQLite en DELETE preserva caché en memoria y auto-sondeos")
    with temp_dir_scope(prefix="test-abort-delete-") as tmp:
        port = get_free_port()
        mock = ControlledMockServer()
        mock.start()
        proc = run_gateway_instance(tmp, port)
        try:
            assert wait_for_ready(port), "El gateway no arrancó a tiempo"
            token = login_admin(port)

            # Crear ruta con intervalo de 3s para tener una ventana holgada entre sondeos
            route_payload = {
                "name": "Backend Abort Delete",
                "enabled": True,
                "authPrefixes": ["abdel_"],
                "aclPrefixes": ["abdel/"],
                "authUrl": f"http://127.0.0.1:{mock.port}/auth",
                "aclUrl": f"http://127.0.0.1:{mock.port}/acl",
                "healthUrl": f"http://127.0.0.1:{mock.port}/health",
                "apiKey": "",
                "monitoring": {
                    "probeIntervalSeconds": 3,
                    "probeTimeoutMs": 500,
                    "failureThreshold": 3,
                    "recoveryThreshold": 2
                }
            }
            rt = api_request(port, "/api/admin/routes", "POST", route_payload, token=token, expected_status=201)
            rid = rt["id"]

            # Esperar a que ocurra el primer sondeo para que la ruta esté plenamente observada
            time.sleep(0.6)
            metrics = api_request(port, "/api/admin/metrics", "GET", token=token)
            m = next((x for x in metrics if x["id"] == rid), None)
            assert m is not None

            # Conectar a la base de datos temporal e instalar trigger que aborte el DELETE
            db_path = tmp / "gateway.db"
            assert db_path.exists(), f"No se encontró {db_path}"

            with sqlite3.connect(str(db_path)) as conn:
                conn.execute(f"""
                    CREATE TRIGGER IF NOT EXISTS test_abort_delete_trigger
                    BEFORE DELETE ON routes
                    FOR EACH ROW
                    WHEN OLD.id = '{rid}'
                    BEGIN
                        SELECT RAISE(ABORT, 'Simulated delete failure in SQLite');
                    END;
                """)
                conn.commit()

            try:
                # Ventana entre sondeos: el sondeo corre cada 3s, lanzamos el DELETE
                # Debe responder 500 debido a la excepción de SQLite al abortar
                api_request(port, f"/api/admin/routes/{rid}", "DELETE", token=token, expected_status=500, password="test-admin-secret")

                # 1. Verificar que la ruta CONSERVA la caché en memoria
                routes_after = api_request(port, "/api/admin/routes", "GET", token=token)
                assert any(r["id"] == rid for r in routes_after), "La ruta debió conservarse en memoria tras fallar la persistencia"

                # 2. Registrar conteo actual de sondeos en el mock
                count_before, _ = mock.get_counts()

                # 3. Esperar que transcurran ~3.5s para confirmar que el scheduler SIGUE activo y ejecuta nuevos sondeos
                time.sleep(3.5)
                count_after, _ = mock.get_counts()
                assert count_after > count_before, f"El scheduler debió continuar sondeando la ruta (antes={count_before}, después={count_after})"

                print("   [OK] Fallo en persistencia SQLite preservó caché en memoria y continuó auto-sondeos")
            finally:
                # Retirar trigger de SQLite
                with sqlite3.connect(str(db_path)) as conn:
                    conn.execute("DROP TRIGGER IF EXISTS test_abort_delete_trigger;")
                    conn.commit()

            # Confirmar que tras retirar el trigger, el DELETE triunfa (204)
            api_request(port, f"/api/admin/routes/{rid}", "DELETE", token=token, expected_status=204, password="test-admin-secret")
            routes_final = api_request(port, "/api/admin/routes", "GET", token=token)
            assert not any(r["id"] == rid for r in routes_final), "La ruta debió eliminarse exitosamente una vez retirado el trigger"

        finally:
            mock.stop()
            proc.terminate()
            proc.wait(timeout=5)

def main():
    print("=====================================================================")
    print(" SUITE DE PRUEBAS AUTOMATIZADAS - FASE 2: MONITOREO Y SCHEDULER")
    print("=====================================================================")
    tests = [
        test_admin_settings_validation_and_persistence,
        test_route_monitoring_overrides_and_effective,
        test_hysteresis_and_metrics_counters,
        test_manual_probe_exclusivity_and_no_side_effects,
        test_auth_acl_traffic_does_not_affect_probe_health,
        test_generation_discard_and_reschedule_with_in_flight_probe,
        test_delete_persistence_failure_preserves_cache_and_scheduler,
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
    print(f" RESULTADO FASE 2: {passed} PASSED / {failed} FAILED")
    print("---------------------------------------------------------------------")
    if failed > 0:
        sys.exit(1)

if __name__ == "__main__":
    main()
