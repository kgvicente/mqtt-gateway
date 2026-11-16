#!/usr/bin/env python3
"""
Benchmark reproducible de rendimiento Linux: Baseline HEAD vs Fase 1 JVM vs Fase 1 Nativo.
Cumple con los requisitos estrictos de validación de Fase 1:
- Verificación de artefactos previos (falla si falta baseline o current).
- Red Docker aislada para comunicación gateway <-> backend mock.
- Backend mock HTTP que verifica recepción y conteo estricto de peticiones delegadas.
- Registro de rutas válidas vía API Admin con autenticación interna.
- Exigencia estricta de JSON {"result": "allow"} en cada petición; cualquier error o deny invalida la ronda.
- Configuración montada fuera de /app:ro en /qa-config/application.yml:ro.
- Volúmenes Docker gestionados en Linux (/data) para medir persistencia e I/O reales en disco (sin tmpfs ni bind Windows).
- Límites idénticos de recursos por modo (--cpus=2.0 --memory=1024m).
- Muestreo concurrente de Docker stats (CPU, RAM, Block I/O) durante la carga activa.
- 3 rondas aisladas de 600 peticiones con concurrencia 10 por cada modo.
- Detección de regresiones >10% repetibles.
- Limpieza estricta de contenedores y volúmenes aislados creados.
"""

import concurrent.futures
import json
import math
import os
import pathlib
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]

MOCK_SCRIPT = """
import http.server, json, sys, threading

count = 0
count_lock = threading.Lock()

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *args): pass
    def do_POST(self):
        global count
        l = int(self.headers.get("Content-Length", 0))
        if l > 0:
            self.rfile.read(l)
        with count_lock:
            count += 1
        b = b'{"result":"allow","is_superuser":false}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        self.wfile.write(b)
    def do_GET(self):
        global count
        if self.path == "/stats":
            with count_lock:
                c = count
            b = json.dumps({"count": c}).encode()
        else:
            b = b'{"status":"up"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

class BenchServer(http.server.ThreadingHTTPServer):
    request_queue_size = 256
    daemon_threads = True
    allow_reuse_address = True

s = BenchServer(("0.0.0.0", 19090), Handler)
s.serve_forever()
"""

class DockerStatsSampler:
    """Muestreador en segundo plano de CPU, RAM e I/O durante la ejecución de carga."""
    def __init__(self, container_name):
        self.container_name = container_name
        self.stop_event = threading.Event()
        self.cpu_samples = []
        self.mem_samples = []
        self.block_io_last = "N/D"
        self.thread = None

    def _sample(self):
        while not self.stop_event.is_set():
            try:
                res = subprocess.run(
                    ["docker", "stats", "--no-stream", "--format", "{{.CPUPerc}};;{{.MemUsage}};;{{.BlockIO}}", self.container_name],
                    capture_output=True, text=True, timeout=2
                )
                if res.returncode == 0 and res.stdout.strip():
                    parts = res.stdout.strip().split(";;")
                    if len(parts) >= 3:
                        cpu_str = parts[0].replace("%", "").strip()
                        try:
                            self.cpu_samples.append(float(cpu_str))
                        except ValueError:
                            pass
                        self.mem_samples.append(parts[1].strip())
                        self.block_io_last = parts[2].strip()
            except Exception:
                pass
            time.sleep(0.2)

    def start(self):
        self.thread = threading.Thread(target=self._sample, daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=3)

    def summary(self):
        if not self.cpu_samples:
            return "CPU: N/D | RAM: N/D | I/O: N/D"
        avg_cpu = sum(self.cpu_samples) / len(self.cpu_samples)
        max_cpu = max(self.cpu_samples)
        last_mem = self.mem_samples[-1] if self.mem_samples else "N/D"
        return f"CPU: {avg_cpu:4.1f}% (max {max_cpu:4.1f}%) | RAM: {last_mem} | I/O: {self.block_io_last}"

def get_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

def wait_ready(port, timeout=25):
    start = time.monotonic()
    url = f"http://127.0.0.1:{port}/q/health"
    while time.monotonic() - start < timeout:
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=1) as resp:
                if resp.status == 200:
                    return True
        except Exception:
            time.sleep(0.2)
    return False

def http_json(url, method="GET", body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=5) as resp:
        if resp.status not in (200, 201, 204):
            raise RuntimeError(f"HTTP {resp.status} de {url}")
        content = resp.read()
        return json.loads(content.decode()) if content else None

def get_mock_count(mock_port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{mock_port}/stats", timeout=2) as resp:
            data = json.loads(resp.read().decode())
            return data.get("count", 0)
    except Exception:
        return 0

def run_benchmark_target(name, container_name, port, mock_port, runs=3, reqs_per_run=600, concurrency=10):
    print(f"\n==================================================")
    print(f" BENCHMARK: {name}")
    print(f"==================================================")

    if not wait_ready(port):
        subprocess.run(["docker", "logs", "--tail", "50", container_name])
        raise RuntimeError(f"El contenedor {container_name} no respondió en el puerto {port}")

    # 1. Login administrativo interno y pausar rutas sembradas para dejar solo bench_
    login_url = f"http://127.0.0.1:{port}/api/admin/auth/login"
    login_resp = http_json(login_url, "POST", {"username": "admin", "password": "bench-admin-password"})
    token = login_resp["token"]

    routes_url = f"http://127.0.0.1:{port}/api/admin/routes"
    existing_routes = http_json(routes_url, "GET", token=token) or []
    for r in existing_routes:
        if r.get("enabled", True):
            r_id = r.get("id")
            r_copy = dict(r)
            r_copy["enabled"] = False
            http_json(f"{routes_url}/{r_id}", "PUT", r_copy, token=token)

    # Crear ruta exclusiva para benchmark apuntando al mock
    route_payload = {
        "name": "Benchmark Core",
        "enabled": True,
        "authPrefixes": ["bench_"],
        "aclPrefixes": ["bench/"],
        "authUrl": "http://perf-mock:19090/auth",
        "aclUrl": "http://perf-mock:19090/acl",
        "healthUrl": "http://perf-mock:19090/health",
        "apiKey": ""
    }
    http_json(routes_url, "POST", route_payload, token=token)

    # 2. Comprobación funcional previa estricta
    test_url = f"http://127.0.0.1:{port}/api/v1/gateway/emqx/auth"
    test_payload = {"clientid": "bench_check", "username": "u", "password": "p"}
    check_resp = http_json(test_url, "POST", test_payload)
    if check_resp.get("result") != "allow":
        raise AssertionError(f"Fallo de verificación funcional en {name}: {check_resp}")

    # 3. Warmup
    for _ in range(50):
        http_json(test_url, "POST", test_payload)

    auth_payload = json.dumps({"clientid": "bench_worker", "username": "u", "password": "p"}).encode()
    headers = {"Content-Type": "application/json"}

    def single_request():
        t0 = time.perf_counter()
        req = urllib.request.Request(test_url, data=auth_payload, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                elapsed = (time.perf_counter() - t0) * 1000.0
                if resp.status != 200:
                    raise RuntimeError(f"HTTP {resp.status} tras {elapsed:.1f}ms")
                body = json.loads(resp.read().decode())
                if body.get("result") != "allow":
                    raise AssertionError(f"Respuesta no permitida tras {elapsed:.1f}ms: {body}")
                return elapsed
        except Exception as e:
            elapsed = (time.perf_counter() - t0) * 1000.0
            print(f"\n[FALLO PETICION ({elapsed:.1f}ms)] {e}")
            try:
                gw_logs = subprocess.run(["docker", "logs", "--tail", "15", container_name], capture_output=True, text=True).stdout
                print(f"--- LOGS GATEWAY ({container_name}) ---\n{gw_logs.strip()}\n--- FIN LOGS ---")
            except Exception:
                pass
            raise RuntimeError(f"Error en petición de benchmark: {e}")

    throughputs = []
    p95s = []
    p99s = []
    stats_list = []

    for r in range(1, runs + 1):
        before_mock = get_mock_count(mock_port)
        latencies = []
        sampler = DockerStatsSampler(container_name)
        sampler.start()
        t_start = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [executor.submit(single_request) for _ in range(reqs_per_run)]
            for f in concurrent.futures.as_completed(futures):
                lat = f.result()
                latencies.append(lat)
        duration = time.perf_counter() - t_start
        sampler.stop()
        count = len(latencies)
        after_mock = get_mock_count(mock_port)
        delta_mock = after_mock - before_mock
        if delta_mock != reqs_per_run:
            raise AssertionError(f"El backend mock no recibió todas las peticiones (esperadas {reqs_per_run}, recibidas {delta_mock})")

        rps = count / duration if duration > 0 else 0
        latencies.sort()
        p95 = latencies[int(math.ceil(0.95 * count)) - 1]
        p99 = latencies[int(math.ceil(0.99 * count)) - 1]

        stat_summary = sampler.summary()
        throughputs.append(rps)
        p95s.append(p95)
        p99s.append(p99)
        stats_list.append(stat_summary)
        print(f"  Ronda {r}: {rps:7.1f} req/s | P95: {p95:5.2f} ms | P99: {p99:5.2f} ms | Mock OK: {delta_mock}/{reqs_per_run} | {stat_summary}")

    return {
        "name": name,
        "throughput": statistics.mean(throughputs),
        "p95": statistics.mean(p95s),
        "p99": statistics.mean(p99s),
        "runs": throughputs,
        "last_stats": stats_list[-1]
    }

def verify_artifacts():
    base_jar = root / "target" / "baseline-src" / "target" / "quarkus-app" / "quarkus-run.jar"
    f1_jar = root / "target" / "quarkus-app" / "quarkus-run.jar"
    f1_runner = root / "target" / "mqtt-gateway-0.2.0-runner"

    for path, desc in [
        (base_jar, "Baseline HEAD quarkus-run.jar"),
        (f1_jar, "Fase 1 JVM quarkus-run.jar"),
        (f1_runner, "Fase 1 Nativo Linux runner")
    ]:
        if not path.is_file():
            raise FileNotFoundError(f"Falta artefacto requerido para benchmark: {desc} en {path}")
        print(f"[OK] Artefacto verificado: {desc} ({path.stat().st_size:,} bytes)")

def main():
    print("Iniciando validación y preparación de benchmark Linux...")
    verify_artifacts()

    network_name = "perf-qa-net"
    mock_container = "perf-mock"
    mock_host_port = get_free_port()

    # Limpieza preventiva por si hubo ejecuciones interrumpidas
    subprocess.run(["docker", "rm", "-f", mock_container, "perf-qa-base", "perf-qa-jvm", "perf-qa-nat"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["docker", "network", "rm", network_name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["docker", "volume", "rm", "-f", "perf-qa-vol-base", "perf-qa-vol-jvm", "perf-qa-vol-nat"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # 1. Crear red aislada
    subprocess.run(["docker", "network", "create", network_name], check=True, stdout=subprocess.DEVNULL)

    # 2. Iniciar mock backend en la red aislada
    mock_cmd = [
        "docker", "run", "-d", "--name", mock_container,
        "--network", network_name,
        "-p", f"127.0.0.1:{mock_host_port}:19090",
        "python:3.13-alpine",
        "python3", "-c", MOCK_SCRIPT
    ]
    subprocess.run(mock_cmd, check=True)
    time.sleep(1)

    # Configuración compartida montada en /qa-config/application.yml
    # Hereda seeds YAML (auth y acl predeterminados)
    cfg_text = """
quarkus:
  http:
    host: 0.0.0.0
    port: 8085
gateway:
  data-dir: /data
  admin:
    auth-provider: internal
    username: admin
    password: bench-admin-password
  superuser:
    username: bench-su
    password: bench-su-pass
  timeout-ms: 2000
  global-topics: [heart, ping]
"""
    tmp_cfg_dir = tempfile.mkdtemp(prefix="qa-bench-cfg-")
    cfg_file = pathlib.Path(tmp_cfg_dir) / "application.yml"
    cfg_file.write_text(cfg_text, encoding="utf-8")

    results = []

    targets = [
        {
            "name": "Baseline HEAD (JVM Linux Temurin-25)",
            "container": "perf-qa-base",
            "volume": "perf-qa-vol-base",
            "image": "eclipse-temurin:25-jre",
            "mount": f"{root.resolve().as_posix()}/target/baseline-src/target/quarkus-app:/app:ro",
            "cmd": ["java", "-Dquarkus.config.locations=file:///qa-config/application.yml", "-jar", "/app/quarkus-run.jar"]
        },
        {
            "name": "Fase 1 SQLite (JVM Linux Temurin-25)",
            "container": "perf-qa-jvm",
            "volume": "perf-qa-vol-jvm",
            "image": "eclipse-temurin:25-jre",
            "mount": f"{root.resolve().as_posix()}/target/quarkus-app:/app:ro",
            "cmd": ["java", "-Dquarkus.config.locations=file:///qa-config/application.yml", "-jar", "/app/quarkus-run.jar"]
        },
        {
            "name": "Fase 1 SQLite (Native Linux Mandrel-25)",
            "container": "perf-qa-nat",
            "volume": "perf-qa-vol-nat",
            "image": "python:3.13-slim",
            "mount": f"{root.resolve().as_posix()}/target/mqtt-gateway-0.2.0-runner:/app/runner:ro",
            "cmd": ["/app/runner", "-Dquarkus.config.locations=file:///qa-config/application.yml"]
        }
    ]

    try:
        for t in targets:
            port = get_free_port()
            subprocess.run(["docker", "volume", "create", t["volume"]], check=True, stdout=subprocess.DEVNULL)
            docker_cmd = [
                "docker", "run", "-d", "--name", t["container"],
                "--network", network_name,
                "--cpus=2.0", "--memory=1024m",
                "-p", f"127.0.0.1:{port}:8085",
                "-v", t["mount"],
                "-v", f"{cfg_file.resolve().as_posix()}:/qa-config/application.yml:ro",
                "-v", f"{t['volume']}:/data",
                t["image"]
            ] + t["cmd"]
            subprocess.run(docker_cmd, check=True)
            try:
                res = run_benchmark_target(t["name"], t["container"], port, mock_host_port, runs=3, reqs_per_run=600, concurrency=10)
                results.append(res)
            finally:
                subprocess.run(["docker", "rm", "-f", t["container"]], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                subprocess.run(["docker", "volume", "rm", "-f", t["volume"]], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        # Resumen comparativo final
        print("\n==========================================================================================")
        print(" RESUMEN COMPARATIVO DE RENDIMIENTO LINUX (3 RONDAS x 600 REQS / 10 CONCURRENTES)")
        print("==========================================================================================")
        print(f"{'Configuración':<40} | {'Throughput (req/s)':<18} | {'P95 (ms)':<10} | {'P99 (ms)':<10} | {'Docker Stats':<40}")
        print("-" * 125)
        for r in results:
            print(f"{r['name']:<40} | {r['throughput']:18.1f} | {r['p95']:10.2f} | {r['p99']:10.2f} | {r.get('last_stats', 'N/D')}")
        print("-" * 125)

        if len(results) >= 2:
            base = results[0]
            f1_jvm = results[1]
            diff_tps = ((f1_jvm['throughput'] - base['throughput']) / base['throughput']) * 100
            diff_p95 = ((f1_jvm['p95'] - base['p95']) / base['p95']) * 100
            print(f"\nComparativa F1 JVM vs Baseline HEAD:")
            print(f"  Throughput:   {f1_jvm['throughput']:.1f} req/s vs {base['throughput']:.1f} req/s ({diff_tps:+.2f}%)")
            print(f"  Latencia P95: {f1_jvm['p95']:.2f} ms vs {base['p95']:.2f} ms ({diff_p95:+.2f}%)")
            if diff_tps < -10.0:
                print("  ALERTA: Regresión de Throughput superior al 10%")
            else:
                print("  ESTADO: APROBADO (variación de Throughput dentro del margen tolerado, >= -10%)")

        if len(results) >= 3:
            f1_nat = results[2]
            diff_nat_tps = ((f1_nat['throughput'] - base['throughput']) / base['throughput']) * 100
            diff_nat_p95 = ((f1_nat['p95'] - base['p95']) / base['p95']) * 100
            print(f"\nComparativa F1 Nativo vs Baseline HEAD:")
            print(f"  Throughput:   {f1_nat['throughput']:.1f} req/s vs {base['throughput']:.1f} req/s ({diff_nat_tps:+.2f}%)")
            print(f"  Latencia P95: {f1_nat['p95']:.2f} ms vs {base['p95']:.2f} ms ({diff_nat_p95:+.2f}%)")
            if diff_nat_tps < -10.0:
                print("  ALERTA: Regresión de Throughput nativo superior al 10%")
            else:
                print("  ESTADO: APROBADO (variación de Throughput dentro del margen tolerado, >= -10%)")

    finally:
        subprocess.run(["docker", "rm", "-f", mock_container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["docker", "network", "rm", network_name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        import shutil
        shutil.rmtree(tmp_cfg_dir, ignore_errors=True)
        print("\n[OK] Limpieza de entorno Docker y directorios temporales completada.")

if __name__ == "__main__":
    main()
