#!/usr/bin/env python3
"""Pruebas rigurosas y reproducibles de QA para la Fase 1: SQLite, migraciones, secretos e importación."""
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
def get_java_executable():
    if "JAVA_HOME" in os.environ:
        cand = pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if cand.exists():
            return str(cand)
    which_java = shutil.which("java")
    if which_java:
        return which_java
    return "java"

java_bin = get_java_executable()
default_jar = str(root / "target/quarkus-app/quarkus-run.jar")

def get_free_port():
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

def temp_dir_scope(prefix):
    return tempfile.TemporaryDirectory(prefix=prefix, ignore_cleanup_errors=True)

def make_config(temp_dir, port, data_dir):
    cfg = (root / "src/main/resources/application.yml").read_text(encoding="utf-8")
    cfg = cfg.replace("port: 8085", f"port: {port}")
    cfg = cfg.replace("${GATEWAY_DATA_DIR:data}", data_dir.as_posix())
    cfg_file = pathlib.Path(temp_dir) / "application.yml"
    cfg_file.write_text(cfg, encoding="utf-8")
    return cfg_file

def start_gateway(cfg_file, log_path, extra_env=None, target_cmd=None, data_dir=None):
    cmd = target_cmd or [java_bin, "-jar", default_jar]
    cmd = list(cmd)
    cfg_uri = pathlib.Path(cfg_file).resolve().as_uri()
    cmd.insert(1, f"-Dquarkus.config.locations={cfg_uri}")
    resolved_data = str(data_dir) if data_dir else (pathlib.Path(cfg_file).parent / "data").as_posix()
    env = dict(os.environ,
               GATEWAY_ADMIN_AUTH_PROVIDER="internal",
               GATEWAY_ADMIN_USERNAME="admin",
               GATEWAY_ADMIN_PASSWORD="test-password",
               GATEWAY_DATA_DIR=resolved_data,
               GATEWAY_MASTER_KEY="MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
               EMQX_BACKEND_API_KEY="fase1-api-key")
    if extra_env:
        env.update(extra_env)
    log_fp = open(log_path, "wb")
    proc = subprocess.Popen(cmd, cwd=root, stdout=log_fp, stderr=subprocess.STDOUT, env=env)
    return proc, log_fp

def wait_for_http(proc, log_path, port, path="/q/health", timeout=15):
    url = f"http://127.0.0.1:{port}{path}"
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        if proc.poll() is not None:
            time.sleep(0.1)
            log_content = pathlib.Path(log_path).read_text(encoding="utf-8", errors="replace")
            raise RuntimeError(f"Proceso Gateway termino prematuramente (codigo {proc.returncode}):\n{log_content}")
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=1) as resp:
                if resp.status == 200:
                    return json.loads(resp.read().decode())
        except Exception:
            time.sleep(0.15)
    log_content = pathlib.Path(log_path).read_text(encoding="utf-8", errors="replace")
    raise TimeoutError(f"No respondio {url} tras {timeout}s. Salida:\n{log_content}")

def http_req(port, path, method="GET", body=None, token=None, expected=200):
    url = f"http://127.0.0.1:{port}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as res:
            raw = res.read().decode("utf-8")
            assert res.status == expected, f"Esperado {expected}, obtenido {res.status}"
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8")
        assert err.code == expected, f"Endpoint {path} retorno {err.code} != esperado {expected}: {raw}"
        return json.loads(raw) if raw and raw.startswith("{") else raw

def test_clean_startup_and_sqlite_pragmas():
    print("-> Test: Clean startup, WAL persistence, and schema migrations")
    port = get_free_port()
    with temp_dir_scope(prefix="test-clean-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        cfg_file = make_config(tmp, port, data_dir)
        log_file = pathlib.Path(tmp) / "gateway.log"
        proc, fp = start_gateway(cfg_file, log_file)
        try:
            health = wait_for_http(proc, log_file, port, "/q/health")
            assert health["status"] == "UP"
            ready = wait_for_http(proc, log_file, port, "/q/health/ready")
            assert ready["status"] == "UP"
            assert any(c["name"] == "storage" and c["status"] == "UP" for c in ready.get("checks", []))

            db_path = data_dir / "gateway.db"
            assert db_path.exists(), "gateway.db debe existir"
            with sqlite3.connect(db_path) as conn:
                # journal_mode persiste en el archivo DB
                journal = conn.execute("PRAGMA journal_mode;").fetchone()[0]
                assert journal.lower() == "wal", f"Journal mode esperado 'wal', obtenido: {journal}"

                migrations = conn.execute("SELECT version, name, checksum FROM schema_migrations;").fetchall()
                assert len(migrations) >= 1, "Debe haber al menos 1 migracion aplicada"
                assert migrations[0][0] == 1
                assert "initial_schema" in migrations[0][1]
                assert len(migrations[0][2]) == 64, "Checksum SHA-256 debe tener 64 caracteres hex"

                seeded = conn.execute("SELECT value FROM settings WHERE key = 'seeded_from_yaml_at';").fetchone()
                assert seeded is not None, "Debe registrar seeded_from_yaml_at"

                routes = conn.execute("SELECT id, name, enabled, api_key_env FROM routes;").fetchall()
                assert len(routes) == 3, f"Deben sembrarse 3 rutas por defecto, obtenidas: {len(routes)}"
        finally:
            proc.terminate()
            proc.wait(timeout=10)
            fp.close()
    print("   [OK] Clean startup, WAL persistente y schema_migrations verificados")

def test_secrets_externalization():
    print("-> Test: Secret write-only (apiKey) & rejection of raw apiKeyEnv")
    port = get_free_port()
    with temp_dir_scope(prefix="test-secrets-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        cfg_file = make_config(tmp, port, data_dir)
        log_file = pathlib.Path(tmp) / "gateway.log"
        proc, fp = start_gateway(cfg_file, log_file)
        try:
            wait_for_http(proc, log_file, port)
            login_resp = http_req(port, "/api/admin/auth/login", method="POST",
                                  body={"username": "admin", "password": "test-password"})
            token = login_resp["token"]

            # 1. Envio de apiKeyEnv nuevo no vacio DEBE ser rechazado con 400
            bad_env_route = {
                "name": "Bad Env Key",
                "enabled": True,
                "authPrefixes": ["bad_"],
                "aclPrefixes": ["bad/"],
                "authUrl": "http://127.0.0.1:9999/auth",
                "aclUrl": "http://127.0.0.1:9999/acl",
                "healthUrl": "http://127.0.0.1:9999/health",
                "apiKeyEnv": "SOME_ENV_VAR"
            }
            res_bad_env = http_req(port, "/api/admin/routes", method="POST", body=bad_env_route, token=token, expected=400)
            assert "apiKeyEnv" in str(res_bad_env)

            # 2. apiKey=null DEBE ser rechazado con 400
            bad_null_route = dict(bad_env_route, apiKey=None)
            del bad_null_route["apiKeyEnv"]
            http_req(port, "/api/admin/routes", method="POST", body=bad_null_route, token=token, expected=400)

            # 3. apiKey no vacio: almacenamiento write-only correcto
            valid_route = dict(bad_env_route, name="Valid Write-Only Secret", authPrefixes=["sec_"], aclPrefixes=["sec/"],
                               apiKey="my-secret-val-999")
            del valid_route["apiKeyEnv"]
            res_valid = http_req(port, "/api/admin/routes", method="POST", body=valid_route, token=token, expected=201)
            rid = res_valid["id"]
            assert res_valid["hasApiKey"] is True
            assert "apiKey" not in res_valid
            assert "apiKeyEnv" not in res_valid

            # 4. Comprobar que en gateway.db NUNCA se guardo "my-secret-val-999" en texto claro
            db_path = data_dir / "gateway.db"
            with sqlite3.connect(db_path) as conn:
                tables = [r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table';").fetchall()]
                dump = " ".join(str(r) for t in tables for r in conn.execute(f"SELECT * FROM {t};").fetchall())
                assert "my-secret-val-999" not in dump, "FALLO CRITICO: Clave secreta encontrada en texto plano en SQLite!"

            # 5. Editar ruta con apiKey="" elimina la clave
            res_valid["apiKey"] = ""
            updated = http_req(port, f"/api/admin/routes/{rid}", method="PUT", body=res_valid, token=token, expected=200)
            assert updated["hasApiKey"] is False

            # 6. Editar ruta omitiendo apiKey preserva la clave previa tras reasignar
            res_valid["apiKey"] = "my-secret-val-999"
            http_req(port, f"/api/admin/routes/{rid}", method="PUT", body=res_valid, token=token, expected=200)
            del res_valid["apiKey"]
            res_valid["name"] = "Preserved Ref Name"
            preserved = http_req(port, f"/api/admin/routes/{rid}", method="PUT", body=res_valid, token=token, expected=200)
            assert preserved["hasApiKey"] is True
        finally:
            proc.terminate()
            proc.wait(timeout=10)
            fp.close()
    print("   [OK] Secretos write-only y rechazo de apiKeyEnv verificados")

def test_tampered_migration_checksum_aborts_startup():
    print("-> Test: Tampered migration checksum aborts startup")
    port = get_free_port()
    with temp_dir_scope(prefix="test-tamper-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        cfg_file = make_config(tmp, port, data_dir)
        log1 = pathlib.Path(tmp) / "gw1.log"
        p1, fp1 = start_gateway(cfg_file, log1)
        wait_for_http(p1, log1, port)
        p1.terminate()
        p1.wait(timeout=10)
        fp1.close()

        db_path = data_dir / "gateway.db"
        with sqlite3.connect(db_path) as conn:
            conn.execute("UPDATE schema_migrations SET checksum = 'corrupted_checksum_000000000000000000000000000000000000000000000000' WHERE version = 1;")
            conn.commit()

        log2 = pathlib.Path(tmp) / "gw2.log"
        p2, fp2 = start_gateway(cfg_file, log2)
        start_time = time.monotonic()
        while time.monotonic() - start_time < 6:
            if p2.poll() is not None:
                break
            time.sleep(0.2)
        code = p2.poll()
        fp2.close()
        assert code is not None, "El gateway debio terminar con error al detectar checksum invalido"
        log_out = log2.read_text(encoding="utf-8", errors="replace")
        assert "checksum" in log_out.lower() or "migraci" in log_out.lower() or code != 0
    print("   [OK] Deteccion de checksum invalido comprobada")

def test_unknown_migration_version_aborts_startup():
    print("-> Test: Unknown migration version in DB aborts startup")
    port = get_free_port()
    with temp_dir_scope(prefix="test-unknown-ver-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        cfg_file = make_config(tmp, port, data_dir)
        log1 = pathlib.Path(tmp) / "gw1.log"
        p1, fp1 = start_gateway(cfg_file, log1)
        wait_for_http(p1, log1, port)
        p1.terminate()
        p1.wait(timeout=10)
        fp1.close()

        db_path = data_dir / "gateway.db"
        with sqlite3.connect(db_path) as conn:
            conn.execute("INSERT INTO schema_migrations (version, name, checksum, applied_at) VALUES (999, 'V999__future.sql', 'fakechecksum', datetime('now'));")
            conn.commit()

        log2 = pathlib.Path(tmp) / "gw2.log"
        p2, fp2 = start_gateway(cfg_file, log2)
        start_time = time.monotonic()
        while time.monotonic() - start_time < 6:
            if p2.poll() is not None:
                break
            time.sleep(0.2)
        code = p2.poll()
        fp2.close()
        assert code is not None, "El gateway debio abortar al detectar version desconocida en schema_migrations"
    print("   [OK] Deteccion de version desconocida de migracion comprobada")

def test_corrupted_json_aborts_startup_and_does_not_seed_empty():
    print("-> Test: Corrupted legacy JSON aborts startup and does NOT seed empty")
    port = get_free_port()
    with temp_dir_scope(prefix="test-corrupt-json-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True)
        (data_dir / "routes.json").write_text("{ corrupt json !! [", encoding="utf-8")
        cfg_file = make_config(tmp, port, data_dir)

        log = pathlib.Path(tmp) / "gw.log"
        p, fp = start_gateway(cfg_file, log)
        start_time = time.monotonic()
        while time.monotonic() - start_time < 6:
            if p.poll() is not None:
                break
            time.sleep(0.2)
        code = p.poll()
        fp.close()
        assert code is not None, "El gateway debe abortar inmediatamente ante un JSON corrupto"
        db_path = data_dir / "gateway.db"
        if db_path.exists():
            with sqlite3.connect(db_path) as conn:
                tables = conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='routes';").fetchall()
                if tables:
                    assert len(conn.execute("SELECT * FROM routes;").fetchall()) == 0, "No debe sembrar rutas tras error de importacion"
    print("   [OK] Aborto ante JSON corrupto sin siembra silenciosa comprobado")

def test_rollback_on_failed_import_with_checkpoint():
    print("-> Test: Rollback on import failure with checkpoint present using trigger fixture")
    port = get_free_port()
    with temp_dir_scope(prefix="test-rollback-chk-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        cfg_file = make_config(tmp, port, data_dir)
        log1 = pathlib.Path(tmp) / "gw1.log"
        # 1. Arrancar para inicializar DB y schema
        p1, fp1 = start_gateway(cfg_file, log1)
        wait_for_http(p1, log1, port)
        p1.terminate()
        p1.wait(timeout=10)
        fp1.close()

        # 2. Borrar datos de prueba de la DB y crear trigger que falle al insertar el marcador
        db_path = data_dir / "gateway.db"
        with sqlite3.connect(db_path) as conn:
            conn.execute("DELETE FROM routes;")
            conn.execute("DELETE FROM incidents;")
            conn.execute("DELETE FROM settings;")
            conn.execute("DELETE FROM checkpoints;")
            conn.execute("""
                CREATE TRIGGER fail_marker BEFORE INSERT ON settings
                WHEN NEW.key = 'imported_from_json_at'
                BEGIN
                    SELECT RAISE(ABORT, 'forced marker failure');
                END;
            """)
            conn.commit()

        # 3. Proveer JSON de rutas, incidentes y daily-state VALIDOS
        routes_data = [{
            "id": "valid-r1", "name": "Valid Route 1", "enabled": True,
            "authPrefixes": ["val_"], "aclPrefixes": ["valid/"],
            "authUrl": "http://127.0.0.1:8090/auth", "aclUrl": "http://127.0.0.1:8090/acl",
            "healthUrl": "http://127.0.0.1:8090/health"
        }]
        incidents_data = [{
            "id": "inc-valid-1", "backendId": "valid-r1", "backend": "Valid Route 1",
            "startedAt": "2026-01-01T00:00:00Z"
        }]
        daily_data = {"date": "2026-01-01", "logs": [], "samples": {}, "probes": {}}
        (data_dir / "routes.json").write_text(json.dumps(routes_data), encoding="utf-8")
        (data_dir / "incidents.json").write_text(json.dumps(incidents_data), encoding="utf-8")
        (data_dir / "daily-state.json").write_text(json.dumps(daily_data), encoding="utf-8")

        # 4. Iniciar gateway: debe intentar importar, insertar rutas/incidents/checkpoint,
        # y al intentar insertar 'imported_from_json_at' el trigger aborta la transacción.
        log2 = pathlib.Path(tmp) / "gw2.log"
        p2, fp2 = start_gateway(cfg_file, log2)
        start_time = time.monotonic()
        while time.monotonic() - start_time < 6:
            if p2.poll() is not None:
                break
            time.sleep(0.2)
        code = p2.poll()
        fp2.close()
        assert code is not None, "El gateway debio abortar ante el fallo del trigger en el marcador"

        # 5. Comprobar que el ROLLBACK fue completo: routes, incidents, checkpoints y settings vacíos
        with sqlite3.connect(db_path) as conn:
            r_cnt = conn.execute("SELECT count(*) FROM routes;").fetchone()[0]
            assert r_cnt == 0, f"Rollback debio dejar routes vacio, encontrado: {r_cnt}"
            i_cnt = conn.execute("SELECT count(*) FROM incidents;").fetchone()[0]
            assert i_cnt == 0, f"Rollback debio dejar incidents vacio, encontrado: {i_cnt}"
            c_cnt = conn.execute("SELECT count(*) FROM checkpoints;").fetchone()[0]
            assert c_cnt == 0, f"Rollback debio dejar checkpoints vacio (sin commit anidado), encontrado: {c_cnt}"
            s_imported = conn.execute("SELECT count(*) FROM settings WHERE key = 'imported_from_json_at';").fetchone()[0]
            assert s_imported == 0, f"Rollback debio dejar settings sin imported_from_json_at, encontrado: {s_imported}"
    print("   [OK] Rollback transaccional estricto con trigger y checkpoint comprobado")

def test_duplicate_route_id_aborts_import():
    print("-> Test: Duplicate route IDs abort import and rollback")
    port = get_free_port()
    with temp_dir_scope(prefix="test-dup-id-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True)

        routes = [
            {"id": "dup-id", "name": "Route 1", "enabled": True, "authPrefixes": ["r1_"], "aclPrefixes": ["r1/"],
             "authUrl": "http://127.0.0.1:8090/auth", "aclUrl": "http://127.0.0.1:8090/acl", "healthUrl": "http://127.0.0.1:8090/h"},
            {"id": "dup-id", "name": "Route 2", "enabled": True, "authPrefixes": ["r2_"], "aclPrefixes": ["r2/"],
             "authUrl": "http://127.0.0.1:8090/auth", "aclUrl": "http://127.0.0.1:8090/acl", "healthUrl": "http://127.0.0.1:8090/h"}
        ]
        (data_dir / "routes.json").write_text(json.dumps(routes), encoding="utf-8")
        (data_dir / "incidents.json").write_text("[]", encoding="utf-8")

        cfg_file = make_config(tmp, port, data_dir)
        log = pathlib.Path(tmp) / "gw.log"
        p, fp = start_gateway(cfg_file, log)
        start_time = time.monotonic()
        while time.monotonic() - start_time < 6:
            if p.poll() is not None:
                break
            time.sleep(0.2)
        fp.close()
        assert p.poll() is not None, "El gateway debe abortar ante IDs duplicados en importacion"
    print("   [OK] Deteccion de IDs duplicados comprobada")

def test_corrupt_json_with_secret_no_leak_in_logs():
    print("-> Test: Corrupt JSON parse does NOT leak secret in logs")
    port = get_free_port()
    with temp_dir_scope(prefix="test-no-leak-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True)

        leak_secret = "CONFIDENTIAL_TOP_SECRET_123456789"
        malformed = f'{{"name": "test", "apiKey": "{leak_secret}", "broken": [}}'
        (data_dir / "routes.json").write_text(malformed, encoding="utf-8")

        cfg_file = make_config(tmp, port, data_dir)
        log = pathlib.Path(tmp) / "gw.log"
        p, fp = start_gateway(cfg_file, log)
        p.wait(timeout=10)
        fp.close()
        combined = log.read_text(encoding="utf-8", errors="replace")
        assert leak_secret not in combined, "FALLO DE SEGURIDAD: El secreto confidencial aparecio en los logs!"
    print("   [OK] Ausencia de filtracion de secretos en logs comprobada")

def test_atomic_legacy_import_and_no_yaml_overwrite_on_reboot():
    print("-> Test: Atomic legacy JSON import and YAML persistence prevention on restart")
    port = get_free_port()
    with temp_dir_scope(prefix="test-import-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True)

        routes_content = [
            {
                "id": "legacy-core",
                "name": "Legacy Core Service",
                "enabled": True,
                "authPrefixes": ["leg_"],
                "aclPrefixes": ["legacy/"],
                "authUrl": "http://127.0.0.1:8090/auth",
                "aclUrl": "http://127.0.0.1:8090/acl",
                "healthUrl": "http://127.0.0.1:8090/health",
                "apiKey": "smoke-backend-api-key",
                "createdAt": "2026-01-01T00:00:00Z"
            }
        ]
        incidents_content = [
            {
                "id": "inc-001",
                "backendId": "legacy-core",
                "backend": "Legacy Core Service",
                "startedAt": "2026-01-02T10:00:00Z",
                "endedAt": "2026-01-02T10:05:00Z",
                "durationMs": 300000,
                "reason": "HTTP 503",
                "endReason": "Recovered"
            }
        ]
        (data_dir / "routes.json").write_text(json.dumps(routes_content), encoding="utf-8")
        (data_dir / "incidents.json").write_text(json.dumps(incidents_content), encoding="utf-8")

        cfg_file = make_config(tmp, port, data_dir)
        extra_env = {
            "EMQX_BACKEND_API_KEY": "smoke-backend-api-key"
        }
        cfg_text = cfg_file.read_text(encoding="utf-8")
        cfg_text = cfg_text.replace("storage:\n    legacy-api-key-env:\n      core-service: EMQX_BACKEND_API_KEY",
                                    "storage:\n    legacy-api-key-env:\n      legacy-core: EMQX_BACKEND_API_KEY")
        cfg_file.write_text(cfg_text, encoding="utf-8")

        log1 = pathlib.Path(tmp) / "gw1.log"
        p, fp = start_gateway(cfg_file, log1, extra_env=extra_env)
        try:
            wait_for_http(p, log1, port)
            db_path = data_dir / "gateway.db"
            assert db_path.exists()
            with sqlite3.connect(db_path) as conn:
                imp = conn.execute("SELECT value FROM settings WHERE key = 'imported_from_json_at';").fetchone()
                assert imp is not None, "Debe registrarse imported_from_json_at"

                r = conn.execute("SELECT id, name, api_key_env FROM routes WHERE id = 'legacy-core';").fetchone()
                assert r is not None, "La ruta legacy-core debe estar en la BD"
                assert r[1] == "Legacy Core Service"
                assert r[2] is None, f"api_key_env debe ser limpiado a NULL tras migrar a managed_secrets, obtenido: {r[2]}"
                sec = conn.execute("SELECT owner FROM managed_secrets WHERE owner = 'backend:legacy-core';").fetchone()
                assert sec is not None, "El secreto de legacy-core debe existir en managed_secrets"

                inc = conn.execute("SELECT id, backend_id, reason FROM incidents WHERE id = 'inc-001';").fetchone()
                assert inc is not None, "Incidente inc-001 debe haberse importado"

                assert (data_dir / "routes.json").exists()
                assert (data_dir / "incidents.json").exists()

            p.terminate()
            p.wait(timeout=10)
            fp.close()

            # Reiniciar el gateway: debe conservar la BD y NO sobrescribir con YAML
            log2 = pathlib.Path(tmp) / "gw2.log"
            p2, fp2 = start_gateway(cfg_file, log2, extra_env=extra_env)
            try:
                wait_for_http(p2, log2, port)
                with sqlite3.connect(db_path) as conn:
                    routes = conn.execute("SELECT id FROM routes;").fetchall()
                    route_ids = [row[0] for row in routes]
                    assert "legacy-core" in route_ids
                    assert "core-service" not in route_ids, "El reinicio NO debe sobrescribir datos de la BD con el YAML"
            finally:
                p2.terminate()
                p2.wait(timeout=10)
                fp2.close()
        finally:
            if p.poll() is None:
                p.terminate()
                p.wait(timeout=5)
                fp.close()
    print("   [OK] Importacion atomica legado y no sobrescritura con YAML comprobados")

def test_restart_with_missing_active_route_env_aborts():
    print("-> Test: Restart with missing active route env variable aborts startup")
    port = get_free_port()
    with temp_dir_scope(prefix="test-restart-env-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        data_dir.mkdir(parents=True, exist_ok=True)
        db_path = data_dir / "gateway.db"

        # Sembrar SQLite con ruta legada que referencia variable de entorno
        with sqlite3.connect(db_path) as conn:
            conn.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, name TEXT, checksum TEXT, applied_at TEXT);")
            conn.execute("INSERT INTO schema_migrations VALUES (1, 'V1__initial_schema.sql', 'c1', '2026-01-01');")
            conn.execute("INSERT INTO schema_migrations VALUES (2, 'V2__monitoring.sql', 'c2', '2026-01-01');")
            conn.execute("INSERT INTO schema_migrations VALUES (3, 'V3__telegram_outbox.sql', 'c3', '2026-01-01');")
            conn.execute("""CREATE TABLE routes (id TEXT PRIMARY KEY, name TEXT, enabled INTEGER, auth_prefixes TEXT, acl_prefixes TEXT,
                            auth_url TEXT, acl_url TEXT, health_url TEXT, api_key_env TEXT, monitoring_json TEXT, telegram_json TEXT,
                            created_at TEXT, updated_at TEXT);""")
            conn.execute("""INSERT INTO routes VALUES ('r-env', 'Route Env', 1, '["act_"]', '["act/"]',
                            'http://127.0.0.1:8080', 'http://127.0.0.1:8080', 'http://127.0.0.1:8080',
                            'MY_EPHEMERAL_KEY', NULL, NULL, '2026-01-01', '2026-01-01');""")
            conn.commit()

        cfg_file = make_config(tmp, port, data_dir)
        log2 = pathlib.Path(tmp) / "gw2.log"

        # Arrancar SIN la variable en el entorno -> debe fallar al intentar migrar la ruta en arranque
        p2, fp2 = start_gateway(cfg_file, log2, extra_env={"MY_EPHEMERAL_KEY": ""})
        start_time = time.monotonic()
        while time.monotonic() - start_time < 6:
            if p2.poll() is not None:
                break
            time.sleep(0.2)
        code = p2.poll()
        fp2.close()
        assert code is not None and code != 0, "El gateway debio abortar en el arranque al faltar variable de entorno de una ruta activa"
    print("   [OK] Aborto por variable de entorno faltante en ruta activa comprobado")

def test_concurrent_delete_and_traffic():
    print("-> Test: Concurrent route deletion and auth traffic")
    port = get_free_port()
    with temp_dir_scope(prefix="test-concurrency-") as tmp:
        data_dir = pathlib.Path(tmp) / "data"
        cfg_file = make_config(tmp, port, data_dir)
        log = pathlib.Path(tmp) / "gw.log"
        p, fp = start_gateway(cfg_file, log)
        try:
            wait_for_http(p, log, port)
            login = http_req(port, "/api/admin/auth/login", method="POST",
                             body={"username": "admin", "password": "test-password"})
            token = login["token"]

            new_route = {
                "name": "Concurrent Route",
                "enabled": True,
                "authPrefixes": ["conc_"],
                "aclPrefixes": ["conc/"],
                "authUrl": f"http://127.0.0.1:{port}/q/health",
                "aclUrl": f"http://127.0.0.1:{port}/q/health",
                "healthUrl": f"http://127.0.0.1:{port}/q/health"
            }
            created = http_req(port, "/api/admin/routes", method="POST", body=new_route, token=token, expected=201)
            rid = created["id"]

            stop_traffic = threading.Event()
            traffic_errors = []

            def send_traffic():
                while not stop_traffic.is_set():
                    try:
                        res = http_req(port, "/api/v1/gateway/emqx/auth", method="POST",
                                       body={"clientid": "conc_test", "username": "u", "password": "p"})
                        if res.get("result") not in ("allow", "deny", "ignore"):
                            traffic_errors.append(f"Respuesta inesperada: {res}")
                    except Exception as ex:
                        traffic_errors.append(str(ex))
                    time.sleep(0.01)

            t = threading.Thread(target=send_traffic, daemon=True)
            t.start()
            time.sleep(0.1)

            # Eliminar la ruta concurrentemente mientras recibe peticiones
            http_req(port, f"/api/admin/routes/{rid}", method="DELETE", token=token, expected=204)
            time.sleep(0.2)
            stop_traffic.set()
            t.join(timeout=2)

            assert len(traffic_errors) == 0, f"Errores en trafico concurrente durante eliminacion: {traffic_errors}"
        finally:
            p.terminate()
            p.wait(timeout=10)
            fp.close()
    print("   [OK] Eliminacion concurrente con trafico sin fallos 500 comprobada")

if __name__ == "__main__":
    print("==================================================")
    print(" INICIANDO QA VALIDATION SUITE FASE 1 (SQLite)    ")
    print("==================================================")
    test_clean_startup_and_sqlite_pragmas()
    test_secrets_externalization()
    test_tampered_migration_checksum_aborts_startup()
    test_unknown_migration_version_aborts_startup()
    test_corrupted_json_aborts_startup_and_does_not_seed_empty()
    test_rollback_on_failed_import_with_checkpoint()
    test_duplicate_route_id_aborts_import()
    test_corrupt_json_with_secret_no_leak_in_logs()
    test_atomic_legacy_import_and_no_yaml_overwrite_on_reboot()
    test_restart_with_missing_active_route_env_aborts()
    test_concurrent_delete_and_traffic()
    print("==================================================")
    print(" TODAS LAS PRUEBAS DE FASE 1 COMPLETADAS CON EXITO")
    print("==================================================")
