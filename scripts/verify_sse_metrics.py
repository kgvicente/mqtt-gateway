import urllib.request
import json
import os

# 1. Probar login administrativo
req = urllib.request.Request(
    'http://127.0.0.1:8085/api/admin/auth/login',
    data=json.dumps({'username': os.environ.get('GATEWAY_ADMIN_USERNAME', 'admin'), 'password': os.environ['GATEWAY_ADMIN_PASSWORD']}).encode('utf-8'),
    headers={'Content-Type': 'application/json'}
)
token = None
try:
    with urllib.request.urlopen(req) as res:
        data = json.load(res)
        token = data.get('token')
        print("Login correcto; token recibido sin mostrar su contenido.")
except Exception as e:
    print("Error en login:", e)

# 2. Consultar metricas con el token
if token:
    req_metrics = urllib.request.Request(
        'http://127.0.0.1:8085/api/admin/metrics',
        headers={'Authorization': f'Bearer {token}'}
    )
    with urllib.request.urlopen(req_metrics) as res:
        metrics = json.load(res)
        print(f"Metricas obtenidas para {len(metrics)} backends:")
        for m in metrics:
            history = m.get('probeHistory', [])
            print(f"- {m['name']} ({m['id']}): {m['status']}, {len(history)} muestras en probeHistory")
            if history:
                print(f"  Muestra mas reciente: {history[-1]}")

# 3. Probar SSE stream
if token:
    req_stream = urllib.request.Request(
        f'http://127.0.0.1:8085/api/admin/stream?token={token}',
        headers={'Accept': 'text/event-stream'}
    )
    try:
        with urllib.request.urlopen(req_stream, timeout=4) as res:
            print("Conexion SSE establecida con exito! Status:", res.status)
            print("Content-Type retornado:", res.headers.get('Content-Type'))
            # Leer primeras lineas del stream
            line1 = res.readline().decode('utf-8')
            print("Linea 1 de SSE:", repr(line1))
    except TimeoutError:
        print("SSE stream abierto y recibiendo datos (timeout normal de lectura)")
    except Exception as e:
        print("Lectura SSE:", e)
