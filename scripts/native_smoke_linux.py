#!/usr/bin/env python3
"""Prueba exhaustiva de integración y ciclo de vida en Linux para el binario nativo."""
import contextlib
import json
import os
import pathlib
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
runner_bin = root / "target/mqtt-gateway-0.2.0-runner"
if not runner_bin.exists():
    sys.exit(f"ERROR: No se encontró el binario nativo en {runner_bin}")

native_port = int(os.environ.get("GATEWAY_NATIVE_PORT", "18090"))
print(f"=== INICIANDO VALIDACIÓN NATIVA LINUX ===")
print(f"Binario: {runner_bin} ({runner_bin.stat().st_size} bytes)")
print(f"Puerto de prueba: {native_port}")
print(f"UID actual: {os.getuid() if hasattr(os, 'getuid') else 'N/A'}")

temp_dir = tempfile.mkdtemp(prefix="qa-native-smoke-")
data_dir = pathlib.Path(temp_dir) / "data"
data_dir.mkdir(parents=True, exist_ok=True)
log_file = pathlib.Path(temp_dir) / "runner.log"

cfg_content = f"""
quarkus:
  http:
    port: {native_port}
gateway:
  data-dir: {data_dir.as_posix()}
"""
cfg_file = pathlib.Path(temp_dir) / "application.yml"
cfg_file.write_text(cfg_content)

env = dict(os.environ,
           GATEWAY_ADMIN_AUTH_PROVIDER="internal",
           GATEWAY_ADMIN_USERNAME="admin",
           GATEWAY_ADMIN_PASSWORD="test-password",
           GATEWAY_DATA_DIR=data_dir.as_posix(),
           GATEWAY_MASTER_KEY="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")

def start_runner():
    cmd = [str(runner_bin), f"-Dquarkus.config.locations={cfg_file.resolve().as_uri()}"]
    fp = open(log_file, "a")
    proc = subprocess.Popen(cmd, stdout=fp, stderr=subprocess.STDOUT, env=env)
    return proc, fp

def wait_for_ready(port=native_port, timeout=15):
    start = time.monotonic()
    url = f"http://127.0.0.1:{port}/q/health/ready"
    while time.monotonic() - start < timeout:
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=1) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode())
                    if data.get("status") == "UP":
                        return data
        except Exception:
            time.sleep(0.15)
    log_txt = log_file.read_text(errors="replace") if log_file.exists() else "Sin log"
    raise TimeoutError(f"No respondió {url} tras {timeout}s.\nLog:\n{log_txt}")

def http_req(path, method="GET", body=None, token=None):
    url = f"http://127.0.0.1:{native_port}{path}"
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=5) as resp:
        content = resp.read()
        return json.loads(content.decode()) if content else None

try:
    print("-> 1. Arrancando runner nativo...")
    p1, fp1 = start_runner()
    ready = wait_for_ready()
    print("   [OK] Runner nativo arrancó y /q/health/ready reportó UP")
    storage_check = next((c for c in ready.get("checks", []) if c.get("name") == "storage"), None)
    assert storage_check is not None and storage_check.get("status") == "UP", f"storage check falló: {ready}"
    print("   [OK] StorageHealthCheck reporta almacenamiento UP")

    print("-> 2. Autenticación y creación de ruta con apiKeyEnv...")
    login_resp = http_req("/api/admin/auth/login", "POST", {"username": "admin", "password": "test-password"})
    token = login_resp["token"]
    assert token, "No se obtuvo token"

    route_payload = {
        "name": "Native Backend",
        "enabled": True,
        "authPrefixes": ["native_"],
        "aclPrefixes": ["native/"],
        "authUrl": "http://127.0.0.1:18090/mock/auth",
        "aclUrl": "http://127.0.0.1:18090/mock/acl",
        "healthUrl": "http://127.0.0.1:18090/mock/health",
        "apiKey": "native-secret-token-999"
    }
    created = http_req("/api/admin/routes", "POST", route_payload, token=token)
    route_id = created["id"]
    print(f"   [OK] Ruta creada con ID: {route_id}, hasApiKey={created.get('hasApiKey')}")
    assert created.get("hasApiKey") is True
    assert "apiKey" not in created

    # Verificar que el backend SQLite está en modo WAL y tiene las migraciones V1 a V4
    db_file = data_dir / "gateway.db"
    assert db_file.exists(), f"gateway.db no existe en {data_dir}"
    with contextlib.closing(sqlite3.connect(db_file)) as conn:
        wal_mode = conn.execute("PRAGMA journal_mode;").fetchone()[0]
        assert wal_mode.lower() == "wal", f"Journal mode no es WAL: {wal_mode}"
        migrations = conn.execute("SELECT version, name, checksum FROM schema_migrations ORDER BY version;").fetchall()
        assert len(migrations) == 4, f"Se esperaban 4 migraciones aplicadas, pero hay {len(migrations)}: {migrations}"
        versions = [m[0] for m in migrations]
        assert versions == [1, 2, 3, 4], f"Versiones de migración inesperadas: {versions}"
        routes = conn.execute("SELECT id, name FROM routes WHERE id = ?;", (route_id,)).fetchall()
        assert len(routes) == 1
        # Verificar que la tabla managed_secrets contiene canary y el secreto cifrado
        secrets_rows = conn.execute("SELECT owner, nonce, ciphertext FROM managed_secrets;").fetchall()
        owners = [r[0] for r in secrets_rows]
        assert "canary" in owners, "Canary debe existir en managed_secrets"
        assert f"backend:{route_id}" in owners, f"Secreto de la ruta {route_id} debe existir en managed_secrets"
        # Comprobar nonces AES-256-GCM: 12 bytes y nonces distintos (sin reutilización)
        nonces = [r[1] for r in secrets_rows]
        assert len(nonces) >= 2, f"Se esperaban al menos 2 registros en managed_secrets: {len(nonces)}"
        for n in nonces:
            assert len(n) == 12, f"Cada nonce AES-GCM debe tener 12 bytes: {len(n)}"
        assert len(set(nonces)) == len(nonces), f"Los nonces generados por SecureRandom deben ser únicos y distintos: {nonces}"
        print("   [OK] Cifrado AES-256-GCM validado: nonces de 12 bytes únicos y distintos (sin nonce-reuse)")
        # Verificar que la base no contiene secretos en texto
        tables = [r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table';").fetchall()]
        all_text = " ".join(str(r) for t in tables for r in conn.execute(f"SELECT * FROM {t};").fetchall())
        assert "native-secret-token-999" not in all_text, "Secreto encontrado en base de datos"
    print("   [OK] Base SQLite verificada: modo WAL, migraciones v1-v4 y sin secretos en texto")

    print("-> 3. Deteniendo runner nativo...")
    p1.terminate()
    try:
        p1.wait(timeout=5)
    except subprocess.TimeoutExpired:
        p1.kill()
    fp1.close()
    time.sleep(0.5)

    print("-> 4. Reiniciando runner nativo para verificar persistencia...")
    p2, fp2 = start_runner()
    wait_for_ready()
    print("   [OK] Runner nativo reinició exitosamente tras parada limpia")

    login2 = http_req("/api/admin/auth/login", "POST", {"username": "admin", "password": "test-password"})
    routes_after = http_req("/api/admin/routes", "GET", token=login2["token"])
    assert any(r["id"] == route_id for r in routes_after), f"La ruta {route_id} no persistió tras reinicio"
    print("   [OK] Persistencia verificada: la ruta se recuperó intacta desde SQLite")

    p2.terminate()
    try:
        p2.wait(timeout=5)
    except subprocess.TimeoutExpired:
        p2.kill()
    fp2.close()

    print("==================================================")
    print("VALIDACIÓN NATIVA LINUX COMPLETADA CON ÉXITO")
    print("==================================================")
    sys.exit(0)

except Exception as e:
    print(f"FALLO EN VALIDACIÓN NATIVA: {e}")
    if log_file.exists():
        print(f"Log runner:\n{log_file.read_text(errors='replace')}")
    sys.exit(1)
finally:
    import shutil
    shutil.rmtree(temp_dir, ignore_errors=True)
