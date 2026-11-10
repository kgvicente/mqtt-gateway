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

From this extracted directory:

    export GATEWAY_ADMIN_PASSWORD='replace-with-your-admin-password'
    java -jar quarkus-app/quarkus-run.jar

Open http://127.0.0.1:8085/gateway/ and sign in as admin using that password.
The gateway binds to loopback by default. State is created under ./data.
You must configure your own HTTP backends; this archive does not include an MQTT
broker, a Java runtime or production credentials.

For the Docker Compose demo and source code:
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
    bundle.writestr(f"{prefix}/RUN.md", instructions)
with archive.open("rb") as source:
    digest = hashlib.file_digest(source, "sha256").hexdigest()
(output / "SHA256SUMS").write_text(f"{digest}  {archive.name}\n", encoding="ascii")
print(f"CORRECTO: {archive.name}; suma SHA-256 en SHA256SUMS.")
