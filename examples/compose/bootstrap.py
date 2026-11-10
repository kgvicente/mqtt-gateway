"""Preparar únicamente las rutas del proyecto Compose de demostración."""
import json
import os
import time
import urllib.error
import urllib.request

BASE = "http://gateway:8085"


def request(path, method="GET", value=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    body = json.dumps(value).encode() if value is not None else None
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


for attempt in range(120):
    try:
        request("/q/health")
        break
    except OSError:
        if attempt == 119:
            raise
        time.sleep(1)

token = request("/api/admin/auth/login", "POST", dict(username="admin", password=os.environ["GATEWAY_ADMIN_PASSWORD"]))["token"]
routes = request("/api/admin/routes", token=token)
# ponytail: pausar las rutas iniciales evita sondeos a servicios ausentes en esta demo.
for route in routes:
    if route["id"] in ("core-service", "device-gw", "telemetry") and route["enabled"]:
        request("/api/admin/routes/" + route["id"], "PUT", dict(route, enabled=False), token)
demo = dict(name="Local demo", enabled=True, authPrefixes=["demo_"], aclPrefixes=["demo/"],
            authUrl="http://backend:19090/auth", aclUrl="http://backend:19090/acl",
            healthUrl="http://backend:19090/health", apiKey="")
existing = next((route for route in routes if route["name"] == "Local demo"), None)
request("/api/admin/routes" + ("/" + existing["id"] if existing else ""),
        "PUT" if existing else "POST", demo, token)
print("CORRECTO: ruta Local demo preparada; credenciales MQTT demo-user / demo-password.")
