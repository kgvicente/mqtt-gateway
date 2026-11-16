"""Empaquetar la distribución JVM compilada y generar su suma SHA-256."""
import hashlib
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
version = ET.parse(root / "pom.xml").getroot().findtext("m:version", namespaces=namespace)
if not version or "SNAPSHOT" in version:
    raise SystemExit("Se requiere una versión de publicación sin SNAPSHOT.")
app = root / "target/quarkus-app"
if not (app / "quarkus-run.jar").is_file():
    raise SystemExit("Primero ejecuta ./mvnw package.")
output = root / "target/releases"
output.mkdir(parents=True, exist_ok=True)
prefix = f"mqtt-gateway-{version}"
archive = output / f"{prefix}-jvm.zip"
instructions = f"""# MQTT Gateway {version}

JVM distribution for Linux or WSL, with JRE/JDK 25 installed.
Native Windows runtime execution is not supported by the POSIX state store.

---

## 1. Master Key & Secrets Setup (Mandatory)

MQTT Gateway encrypts managed secrets (e.g. Telegram bot tokens) with AES-256-GCM
using an external, mandatory 32-byte Base64 key (`GATEWAY_MASTER_KEY`).

Prepare a private `master.env` file once in a secure location outside the repository
(following the exact procedure from the included guide `docs/telegram-docker.es.md`):

```bash
master_env_path="${{XDG_CONFIG_HOME:-$HOME/.config}}/mqtt-gateway/master.env"
if [ ! -f "$master_env_path" ]; then
  mkdir -p "$(dirname "$master_env_path")" || exit 1
  chmod 700 "$(dirname "$master_env_path")" || exit 1
  (
    set -o noclobber
    umask 077
    master_key=$(openssl rand -base64 32) || exit 1
    [ -n "$master_key" ] || exit 1
    printf 'GATEWAY_MASTER_KEY=%s\n' "$master_key" > "$master_env_path" || exit 1
    unset master_key
  ) || {{ echo "Error: no se pudo generar o crear master.env en $master_env_path." >&2; exit 1; }}
  chmod 600 "$master_env_path" || exit 1
  echo "Archivo master.env creado en $master_env_path con permisos restringidos (600)."
else
  echo "master.env ya existe en $master_env_path; se reutiliza la clave existente para preservar los secretos cifrados."
fi
```

> **IMPORTANT - BACKUP YOUR MASTER KEY**:
> Safely back up `$master_env_path` outside the server (note that XDG paths may vary).
> If the master key is lost, encrypted credentials in SQLite (`./data/gateway.db`)
> cannot be decrypted. Always reuse the same key for the same database.

---

## 2. Load Environment & Run

Before launching Java in Bash, load your master key and set your administrative password:

```bash
master_env_path="${{XDG_CONFIG_HOME:-$HOME/.config}}/mqtt-gateway/master.env"

# Load master.env into the current shell:
set -a
. "$master_env_path"
set +a

# Set admin password for web console access:
export GATEWAY_ADMIN_PASSWORD='replace-with-your-admin-password'

# Launch the gateway from this extracted directory:
java -jar quarkus-app/quarkus-run.jar
```

---

## 3. Web Console & Persistent State

- **Web Admin Console**: http://127.0.0.1:8085/gateway/
  Sign in as `admin` using the value configured in `GATEWAY_ADMIN_PASSWORD`.
- **Health Check**: `curl http://127.0.0.1:8085/q/health/ready`
- **State Store**: SQLite and audit logs are stored under `./data` (POSIX permissions 0700).
  Ensure `./data` resides on a persistent, backed-up filesystem.
- **Network**: The gateway binds to loopback (`127.0.0.1`) by default.
  You must configure your own HTTP backends; this archive does not include an MQTT
  broker, a Java runtime, or production credentials.

---

## 4. Offline Documentation Included

Offline guides are included in the `docs/` folder:
- `docs/reference.es.md`: Endpoint specifications, configuration parameters, and architecture.
- `docs/telegram-docker.es.md`: Telegram notifications guide, Docker deployment, and master key procedures.

For Docker Compose demos, updates, and source code:
https://github.com/kgvicente/mqtt-gateway/tree/v{version}

Project-owned code: GNU Affero General Public License v3.0 (AGPL-3.0). See LICENSE and NOTICE.
Commercial licensing: https://github.com/kgvicente/mqtt-gateway#commercial-licensing
"""
# ponytail: distribuir el fast-jar completo evita presentar el JAR principal como autosuficiente.
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for file in sorted(app.rglob("*")):
        if file.is_file():
            bundle.write(file, f"{prefix}/quarkus-app/{file.relative_to(app).as_posix()}")
    for name in ("LICENSE", "NOTICE", "CHANGELOG.md"):
        bundle.write(root / name, f"{prefix}/{name}")
    for file in sorted((root / "licenses").glob("*")):
        if file.is_file():
            bundle.write(file, f"{prefix}/licenses/{file.name}")
    for doc in ("telegram-docker.es.md", "reference.es.md"):
        doc_path = root / "docs" / doc
        if doc_path.is_file():
            bundle.write(doc_path, f"{prefix}/docs/{doc}")
    bundle.writestr(f"{prefix}/RUN.md", instructions)
with archive.open("rb") as source:
    digest = hashlib.file_digest(source, "sha256").hexdigest()
(output / "SHA256SUMS").write_text(f"{digest}  {archive.name}\n", encoding="ascii")
print(f"CORRECTO: {archive.name}; suma SHA-256 en SHA256SUMS.")
