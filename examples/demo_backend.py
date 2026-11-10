"""Backend local de demostración; no usar para autenticar usuarios reales."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Backend(BaseHTTPRequestHandler):
    def reply(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, {"status": "up"})
        self.reply(404, {"error": "Ruta no encontrada"})

    def do_POST(self):
        if self.path not in ("/auth", "/acl"):
            return self.reply(404, {"result": "deny"})
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            if not isinstance(body, dict):
                raise ValueError()
        except (ValueError, UnicodeDecodeError):
            return self.reply(400, {"result": "deny"})

        # ponytail: credenciales fijas y un prefijo local, sin otra dependencia para la demo.
        allowed = body.get("username") == "demo-user"
        if self.path == "/auth":
            client = body.get("clientid")
            allowed = (allowed and body.get("password") == "demo-password"
                       and isinstance(client, str) and client.startswith("demo_"))
        else:
            topic = body.get("topic")
            allowed = (allowed and isinstance(topic, str) and topic.startswith("demo/")
                       and not topic.startswith("demo/private/")
                       and body.get("action") in ("publish", "subscribe"))
        self.reply(200, {"result": "allow" if allowed else "deny"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=19090)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Backend)
    print(f"Backend de demostración: http://{args.host}:{server.server_port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
