# Prueba MQTT Gateway en tu equipo

[English](quickstart.md) | [Español](quickstart.es.md) | [Presentación](../README.es.md)

Para una demo con bróker MQTT real, consulta la [guía de Docker Compose](compose.es.md).

Ejecuta el gateway con un backend HTTP pequeño para probar autenticación y permisos
de tópicos. Esta demostración HTTP no requiere bróker MQTT, servidor de base de datos externo, Docker
ni una cuenta EMQX. No establece una conexión MQTT real.

Usa Linux o WSL con JDK 25, Python 3, Git y curl. El almacenamiento actual utiliza
permisos POSIX. En Windows se puede compilar el JAR, pero la ejecución nativa de
Windows todavía no está soportada. Maven Wrapper descarga Maven y las dependencias.

## 1. Descarga el código e inicia el backend de demostración

En la terminal 1:

```sh
git clone https://github.com/kgvicente/mqtt-gateway.git
cd mqtt-gateway
python3 examples/demo_backend.py
```

El backend escucha en `127.0.0.1:19090`. Sus credenciales fijas son
`demo-user` / `demo-password`; solo sirven para este ejemplo.

## 2. Compila e inicia el gateway

En la terminal 2, desde el mismo directorio del repositorio:

Carga tu clave maestra criptográfica desde el archivo privado persistido `master.env` fuera del repositorio (creado una sola vez siguiendo el [procedimiento seguro en la guía de Telegram](telegram-docker.es.md#2-preparación-de-la-clave-maestra-masterenv)). Si la base de datos `data/gateway.db` ya fue creada con secretos, reutilizar siempre la clave persistida garantiza que el canary criptográfico valide el arranque:

En Linux / macOS (Bash):

```sh
# 1. Cargar clave maestra desde el archivo privado persistido fuera del repositorio:
master_env_path="${XDG_CONFIG_HOME:-$HOME/.config}/mqtt-gateway/master.env"
# Si aún no existe, genéralo una sola vez según la guía de Telegram
set -a
. "$master_env_path"
set +a

# 2. Variables de autenticación administrativa:
export GATEWAY_ADMIN_AUTH_PROVIDER=internal
export GATEWAY_ADMIN_USERNAME=admin
export GATEWAY_ADMIN_PASSWORD='change-this-local-demo-password'

# 3. Compilar e iniciar el gateway
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

*(En Windows PowerShell: carga `master.env` persistido en el proceso sin imprimir la clave:)*

```powershell
$masterEnvPath = Join-Path $env:USERPROFILE '.config\mqtt-gateway\master.env'
Get-Content $masterEnvPath | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
        [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
$env:GATEWAY_ADMIN_AUTH_PROVIDER = 'internal'
$env:GATEWAY_ADMIN_USERNAME = 'admin'
$env:GATEWAY_ADMIN_PASSWORD = 'change-this-local-demo-password'
./mvnw.cmd package
java -jar target/quarkus-app/quarkus-run.jar
```

> [!NOTE]
> `GATEWAY_MASTER_KEY` es una clave obligatoria de 32 bytes en Base64 para el cifrado AES-256-GCM en SQLite. Mantenerla en el archivo privado persistido `master.env` fuera del repositorio asegura que la clave se preserve entre sesiones de shell y reinicios sin exponer secretos en consola o historial.

Abre <http://127.0.0.1:8085/gateway/> e ingresa como `admin` con la contraseña
anterior. La documentación está en <http://127.0.0.1:8085/gateway/docs>.

Las tres rutas iniciales pueden aparecer desconectadas si sus servicios no están
ejecutándose. Eso no impide añadir el backend de demostración.

## 3. Registra la ruta de demostración

En la terminal 3:

```sh
TOKEN=$(curl --fail-with-body -sS http://127.0.0.1:8085/api/admin/auth/login \
  -H 'Content-Type: application/json' \
  --data '{"username":"admin","password":"change-this-local-demo-password"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl --fail-with-body -sS http://127.0.0.1:8085/api/admin/routes \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"name":"Local demo","enabled":true,"authPrefixes":["demo_"],"aclPrefixes":["demo/"],"authUrl":"http://127.0.0.1:19090/auth","aclUrl":"http://127.0.0.1:19090/acl","healthUrl":"http://127.0.0.1:19090/health"}'
```

También puedes pulsar **Registrar backend** e introducir esas URLs y prefijos.
Si la ruta ya existe, edítala en lugar de registrarla otra vez.

## 4. Comprueba allow y deny

Estas peticiones usan el adaptador genérico. Su respuesta contiene `allowed` y
`status`; el backend responde con el contrato compartido `result`.

```sh
# Credenciales válidas: {"allowed":true,"status":"allow"}
curl --fail-with-body -sS http://127.0.0.1:8085/api/v1/gateway/generic/auth \
  -H 'Content-Type: application/json' \
  --data '{"clientid":"demo_sensor","username":"demo-user","password":"demo-password"}'

# Contraseña incorrecta: {"allowed":false,"status":"deny"}
curl --fail-with-body -sS http://127.0.0.1:8085/api/v1/gateway/generic/auth \
  -H 'Content-Type: application/json' \
  --data '{"clientid":"demo_sensor","username":"demo-user","password":"wrong"}'

# Tópico y acción permitidos: {"allowed":true,"status":"allow"}
curl --fail-with-body -sS http://127.0.0.1:8085/api/v1/gateway/generic/acl \
  -H 'Content-Type: application/json' \
  --data '{"clientid":"demo_sensor","username":"demo-user","topic":"demo/temperature","action":"publish"}'

# Sin ruta coincidente: {"allowed":false,"status":"ignore"}
curl --fail-with-body -sS http://127.0.0.1:8085/api/v1/gateway/generic/auth \
  -H 'Content-Type: application/json' \
  --data '{"clientid":"unmatched_sensor","username":"demo-user","password":"demo-password"}'
```

El orden de campos del JSON puede variar. Pulsa **Probar conexión** en el backend
de demostración y abre **Detalle** para consultar sondeos y auditoría. Con el
intervalo predeterminado de 30 segundos, el historial se llena gradualmente.

## 5. Detén o restablece la demostración

Pulsa Ctrl+C en las terminales 1 y 2. Las rutas e incidentes permanecen en SQLite embebido (`data/gateway.db`);
puedes reiniciar para seguir usándolos. Pausa o elimina la ruta de demostración
desde el panel cuando termines. La UI pide la contraseña administrativa
configurada al eliminar una ruta.

El backend solo escucha en loopback y utiliza reglas fijas de ejemplo. Sustitúyelo
por tu propio servicio de autenticación y autorización para un despliegue.
Configura credenciales administrativas y de superusuario MQTT propias antes
de exponer el gateway fuera de tu equipo.

## Comprobaciones automatizadas

Desde el repositorio, con el JAR ya compilado:

```sh
python3 scripts/smoke.py
```

La suite inicia un gateway aislado en el puerto 18085, con estado temporal y
dependencias HTTP simuladas. No necesita el backend de demostración ni un bróker.

## Licencia

El código propio está disponible bajo [GNU AGPL-3.0](../LICENSE).
Los usos comerciales fuera de sus permisos requieren una licencia aparte del
titular. Los componentes de terceros conservan [sus propias licencias](../NOTICE).
