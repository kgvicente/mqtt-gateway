# Guía de Puesta en Marcha: Telegram en Docker Compose (Secretos Administrados)

Esta guía describe el procedimiento para configurar las alertas de incidentes por Telegram en el despliegue con Docker Compose de **MQTT Gateway**, utilizando la arquitectura de **secretos administrados desde el panel** cifrados en SQLite con AES-256-GCM y autenticados mediante la clave maestra (`GATEWAY_MASTER_KEY`).

> [!NOTE]
> **Fuente de verdad:** La única autoridad técnica y contractual del sistema es el código fuente del repositorio ([AdminResource.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/AdminResource.java), [GatewayState.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/GatewayState.java), [StorageService.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/StorageService.java), [GatewayConfig.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/GatewayConfig.java), [application.yml](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/resources/application.yml)). Planes y reportes de auditoría son memorias históricas de diseño.

---

## 1. Arquitectura de Secretos Administrados y Clave Maestra

A partir de la versión V4, MQTT Gateway unifica la gestión de credenciales en un único modo operativo:
* Los secretos de negocio (como el **Token del Bot de Telegram** y las **claves API de backends**) se configuran **directamente desde la interfaz web o la API administrativa**, y se almacenan cifrados con **AES-256-GCM** en la base de datos SQLite (`data/gateway.db`).
* Ya **no se declaran variables de entorno para tokens o claves individuales** en el contenedor (`GATEWAY_TELEGRAM_BOT_TOKEN` y `apiKeyEnv` han sido sustituidos por este modelo).
* El motor criptográfico utiliza **directamente la clave maestra (`GATEWAY_MASTER_KEY`)** como clave secreta AES (`AES/GCM/NoPadding`). Esta clave debe ser una cadena de exactamente **32 bytes aleatorios codificados en Base64 estándar**.
* El archivo `compose.yaml` base del proyecto ya declara la variable en el servicio `gateway`:
  ```yaml
  GATEWAY_MASTER_KEY: ${GATEWAY_MASTER_KEY:?Variable GATEWAY_MASTER_KEY obligatoria (Base64 de 32 bytes)}
  ```
  Por tanto, **no se requiere ningún archivo de override**; basta con suministrar la variable a Docker Compose mediante un archivo de entorno protegido (`--env-file`) o desde las variables del sistema.

> [!IMPORTANT]
> **Seguridad y Respaldo de la Clave Maestra:**
> * La clave maestra **nunca se almacena en la base de datos SQLite**. Debe suministrarse exclusivamente a través del entorno del proceso al arrancar.
> * Si la base de datos ya contiene secretos cifrados previamente, **debes utilizar siempre la misma clave maestra original**. Generar una clave distinta impedirá arrancar el gateway, ya que el canary cifrado detectará la discrepancia.
> * Si la clave maestra falta o es inválida, el gateway **aborta el inicio de inmediato** con un mensaje de error saneado.
> * Guarda un respaldo seguro de `GATEWAY_MASTER_KEY` en tu gestor de contraseñas fuera del servidor. Si se extravía la clave maestra, los datos operativos (rutas y configuraciones) se conservan, pero **los secretos cifrados no podrán recuperarse**.

---

## 2. Preparación de la Clave Maestra (`master.env`)

Para evitar teclear la clave en comandos interactivos o exponerla en el repositorio o en el historial de comandos de la shell (`.bash_history` o `PSReadLine`), se crea **una sola vez** un archivo de entorno privado `master.env` en una **ruta segura fuera del repositorio**, con permisos de lectura restringidos exclusivamente al usuario operador (los archivos de claves maestras nunca deben residir en el árbol de trabajo).

Las rutas canónicas recomendadas fuera del repositorio son:
- **En Linux / macOS (Bash):** `${XDG_CONFIG_HOME:-$HOME/.config}/mqtt-gateway/master.env`
- **En Windows (PowerShell):** `Join-Path $env:USERPROFILE '.config\mqtt-gateway\master.env'`

### Procedimiento en Linux / macOS (Bash)

```bash
# 1. Definir la ruta fuera del repositorio y crear master.env UNA SOLA VEZ si no existe
master_env_path="${XDG_CONFIG_HOME:-$HOME/.config}/mqtt-gateway/master.env"
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
  ) || { echo "Error: no se pudo generar o crear master.env en $master_env_path." >&2; exit 1; }
  chmod 600 "$master_env_path" || exit 1
  echo "Archivo master.env creado en $master_env_path con permisos restringidos (600)."
else
  echo "master.env ya existe en $master_env_path; se reutiliza la clave existente para preservar los secretos cifrados."
fi

# 2. Respalda el contenido del archivo en tu gestor de contraseñas seguro (sin imprimir en pantalla ni exponer la clave en logs)
```

### Procedimiento en Windows (PowerShell)

```powershell
# 1. Definir la ruta fuera del repositorio y crear master.env UNA SOLA VEZ si no existe
$masterEnvPath = Join-Path $env:USERPROFILE '.config\mqtt-gateway\master.env'
$masterDir = Split-Path $masterEnvPath -Parent

if (-not (Test-Path $masterEnvPath)) {
    if (-not (Test-Path $masterDir)) {
        New-Item -ItemType Directory -Path $masterDir -Force | Out-Null
    }
    # Restringir permisos del directorio al usuario actual sin herencia
    $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($currentUser, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -Path $masterDir -AclObject $acl

    # Generar clave criptográfica de 32 bytes en Base64
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 32
    $rng.GetBytes($bytes)
    $keyBase64 = [Convert]::ToBase64String($bytes)

    # Escribir usando FileStream con CreateNew para asegurar exclusividad y nunca sobrescribir
    $fs = [System.IO.File]::Open($masterEnvPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    $writer = New-Object System.IO.StreamWriter($fs, [System.Text.Encoding]::ASCII)
    $writer.Write("GATEWAY_MASTER_KEY=$keyBase64`n")
    $writer.Flush()
    $writer.Close()
    $fs.Close()

    $bytes = $null
    $keyBase64 = $null
    Write-Host "Archivo master.env creado en $masterEnvPath con permisos privados restringidos."
} else {
    Write-Host "master.env ya existe en $masterEnvPath; se reutiliza la clave existente para preservar los secretos cifrados."
}

# 2. Respalda el valor en tu gestor de contraseñas seguro (evita imprimir la clave en consola o logs)
```

---

## 3. Puesta en Marcha con Docker Compose

Con el archivo `master.env` preparado fuera del repositorio, inicia o recrea el servicio pasando la variable con la ruta del archivo a Docker Compose.
Mantener el nombre de proyecto existente (`-p mqtt-gateway-preview` o el de tu instalación) preserva el volumen de datos `gateway-data` y la base de datos SQLite intacta:

```bash
# En Linux / macOS (usando la variable con la ruta a master.env fuera del repo):
master_env_path="${XDG_CONFIG_HOME:-$HOME/.config}/mqtt-gateway/master.env"
docker compose -p mqtt-gateway-preview --env-file "$master_env_path" up -d --no-deps gateway
```

```powershell
# En Windows (PowerShell, usando la variable con la ruta a master.env fuera del repo):
$masterEnvPath = Join-Path $env:USERPROFILE '.config\mqtt-gateway\master.env'
docker compose -p mqtt-gateway-preview --env-file $masterEnvPath up -d --no-deps gateway
```

> [!CAUTION]
> **Inspección de metadatos en Docker:**
> Comandos como `docker inspect <contenedor>` o `docker compose config` muestran los metadatos de las variables de entorno del contenedor en el host. Evita compartir salidas completas de inspección en foros públicos o registros de incidencias.

---

## 4. Obtención del Chat ID con @BotFather

### Paso 1: Crear o Designar un Bot en Telegram
1. En Telegram, inicia una conversación con **`@BotFather`** y envía `/newbot`.
2. Sigue las instrucciones para asignar nombre y usuario terminado en `bot`.
3. Guarda el token provisto.

> [!IMPORTANT]
> **Recomendación sobre Webhooks:**
> Conforme a la [documentación oficial de Telegram sobre getUpdates](https://core.telegram.org/bots/api#getupdates) y las [Preguntas Frecuentes de Telegram Bots](https://core.telegram.org/bots/faq), el método `getUpdates` **no opera cuando un bot tiene un Webhook activo**.
> Si ya utilizas un bot existente con un webhook configurado para otra aplicación, **no elimines el webhook** (romperías tu otra integración). Utiliza un bot dedicado exclusivo para MQTT Gateway o consulta el Chat ID a través de los registros de tu integración existente.

### Paso 2: Preparar el Chat de Destino
* **Para un Chat Directo:** Abre el chat con tu bot y pulsa **Iniciar** (`/start`).
* **Para un Grupo:** Añade el bot al grupo y envía un mensaje (ej. `/start` o `@TuBot prueba`). Si el bot tiene el modo de privacidad activo (`/setprivacy`), solo procesará mensajes con barra o menciones directas.
* **Para un Canal:** Añade al bot como **Administrador** con permisos para **Publicar mensajes**. Para generar un evento que permita registrar el Chat ID, **publica un mensaje o post de prueba en el canal**.

### Paso 3: Consultar el Chat ID desde la Consola

Dado que la imagen de Docker del Gateway ejecuta una distribución mínima de Java **sin intérprete de Python dentro del contenedor**, la consulta se ejecuta pasando un script ligero por entrada estándar (STDIN) a un contenedor temporal de Python, sin registrar el token en el historial de comandos:

#### Procedimiento en Windows (PowerShell)

Utiliza `Read-Host -AsSecureString` y pasa el script Python por STDIN a un contenedor efímero de Docker:

```powershell
$secToken = Read-Host -Prompt "Token para consultar Chat ID" -AsSecureString
try {
    $env:GATEWAY_TELEGRAM_BOT_TOKEN = [System.Net.NetworkCredential]::new("", $secToken).Password

    # Con contenedor temporal de Docker (sin requerir Python en el host):
    @'
import os, json, urllib.request
token = os.environ.get("GATEWAY_TELEGRAM_BOT_TOKEN", "").strip()
if not token:
    raise SystemExit("Error: Variable no definida.")
try:
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/getUpdates")
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())
        chats = {}
        for item in data.get("result", []):
            msg = (item.get("message") or item.get("channel_post") or
                   item.get("edited_message") or item.get("edited_channel_post") or
                   item.get("my_chat_member"))
            if msg and "chat" in msg:
                c = msg["chat"]
                chats[str(c["id"])] = c.get("title") or c.get("username") or c.get("first_name")
        print("Chats encontrados:")
        print(json.dumps(chats, indent=2) if chats else "Sin mensajes recientes. Envia un mensaje o publica en el canal y reintenta.")
except Exception:
    print("Error al consultar api.telegram.org. Comprueba conexion y validez de la credencial.")
'@ | docker run --rm -i --env GATEWAY_TELEGRAM_BOT_TOKEN python:3.13-alpine python -
} finally {
    $secToken.Dispose()
    $env:GATEWAY_TELEGRAM_BOT_TOKEN = $null
    Remove-Item env:GATEWAY_TELEGRAM_BOT_TOKEN -ErrorAction SilentlyContinue
}
```

#### Procedimiento en Linux / macOS (Bash)

Utiliza una subshell protegida con `trap` y pasa el script por STDIN a un contenedor temporal:

```bash
(
  set -e
  trap 'unset GATEWAY_TELEGRAM_BOT_TOKEN' EXIT INT TERM
  read -r -s -p "Token para consultar Chat ID: " GATEWAY_TELEGRAM_BOT_TOKEN
  echo ""
  export GATEWAY_TELEGRAM_BOT_TOKEN

  cat << 'EOF' | docker run --rm -i --env GATEWAY_TELEGRAM_BOT_TOKEN python:3.13-alpine python -
import os, json, urllib.request
token = os.environ.get("GATEWAY_TELEGRAM_BOT_TOKEN", "").strip()
if not token:
    raise SystemExit("Error: Variable no definida.")
try:
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/getUpdates")
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())
        chats = {}
        for item in data.get("result", []):
            msg = (item.get("message") or item.get("channel_post") or
                   item.get("edited_message") or item.get("edited_channel_post") or
                   item.get("my_chat_member"))
            if msg and "chat" in msg:
                c = msg["chat"]
                chats[str(c["id"])] = c.get("title") or c.get("username") or c.get("first_name")
        print("Chats encontrados:")
        print(json.dumps(chats, indent=2) if chats else "Sin mensajes recientes. Envia un mensaje o publica en el canal y reintenta.")
except Exception:
    print("Error al consultar api.telegram.org. Comprueba conexion y validez de la credencial.")
EOF
)
```

* Identificadores de chats directos: números enteros positivos (ej. `987654321`).
* Identificadores de grupos y supergrupos: enteros negativos (ej. `-1001234567890`).

---

## 5. Activación en el Panel Web

1. Abre el panel: [http://127.0.0.1:18085/gateway/](http://127.0.0.1:18085/gateway/).
2. Inicia sesión con tus credenciales (`admin` / `local-demo-admin-2026` en la demo local).
3. Haz clic en **Ajustes globales** (icono de engranaje).
4. Configura la sección de Telegram en una única operación atómica:
   - En **Token del Bot de Telegram (BotFather)**: escribe o pega el token de tu bot. El campo es *write-only* y se almacena cifrado en SQLite con AES-256-GCM.
   - En **Chat ID predeterminado**: ingresa el identificador de chat obtenido en el paso anterior.
   - Marca la casilla **Habilitar notificaciones por Telegram**.
   - Pulsa **Guardar ajustes**.
5. Los cambios se aplican de forma inmediata en memoria RAM **sin necesidad de reiniciar el contenedor**.

> [!NOTE]
> **Alcance de `tokenConfigured` y Reglas de Edición:**
> * Al reabrir Ajustes globales o cancelar la ventana, el campo del token se presenta **siempre vacío** por seguridad (conservar por omisión).
> * Si el token ya está configurado en la base de datos, se muestra el distintivo **Token de bot configurado** (`tokenConfigured: true`) y una casilla explícita para eliminarlo si se desea.
> * El indicador certifica la existencia del token en la base de datos; no valida ante Telegram si el token es legítimo ni si el bot cuenta con permisos de publicación en el chat destino.
> * Eliminar el token del bot exige que las alertas se desactiven en la misma solicitud (o la API retornará HTTP 400).

---

## 6. Prueba y Diagnóstico

### Envío de Notificación de Prueba
1. Ve a la pestaña **Notificaciones**.
2. Pulsa **Enviar prueba**, selecciona el destino y pulsa **Encolar prueba**.
3. El endpoint `POST /api/admin/notifications/test` responde **HTTP 202 Accepted** (`{ "id": "<uuid>", "status": "pending" }`):
   - El código 202 confirma que la petición fue validada y colocada en la cola outbox; **no implica entrega inmediata**.
   - El despachador procesará el mensaje en segundo plano con un límite de ritmo de al menos 3 segundos entre envíos al mismo chat.

### Estados en el Historial
* **`pending`**: Registrado en base de datos a la espera de despacho.
* **`sending`**: Solicitud HTTP en curso hacia Telegram.
* **`sent`**: La API de Telegram respondió `HTTP 200` con `{"ok": true}`. Verifica la llegada del mensaje en tu aplicación de Telegram.
* **`failed`**: El envío falló definitivamente (por error `HTTP 4xx` permanente o al agotar los 5 intentos totales).
* **`not_enqueued`**: Descartado por cola llena (límite de 10 000 eventos en cola activa).

### Diagnóstico de Respuestas

| Error | Causa | Solución |
| :--- | :--- | :--- |
| **HTTP 401** | Token inválido o revocado. | Genera un nuevo token con `@BotFather` e ingrésalo en **Ajustes globales** desde el panel web (se aplica en caliente sin reiniciar). |
| **HTTP 403** | Permisos insuficientes (bot no iniciado, expulsado o sin rol de administrador en canal). | Inicia el chat directo con `/start` o asigna rol de Administrador al bot en el canal con permiso de publicación. |
| **HTTP 429** | Límite de tasa de Telegram alcanzado. | El sistema espera los segundos indicados en el parámetro JSON `parameters.retry_after` antes de reintentar. |
| **`tokenConfigured: false`** | No se ha configurado ningún token. | Abre **Ajustes globales** en el panel web e ingresa el token del bot. |

> [!NOTE]
> **Estado de Pruebas Automatizadas:**
> Las 35 pruebas JUnit (12 de monitoreo con histéresis, 14 de outbox/SQLite y 9 de secretos administrados/canary criptográfico) corren en JVM; suites HTTP Python con proveedor simulado verificadas en JVM y nativo Linux. La creación BotFather y recepción en chat REAL siguen pendientes; el botón permite al operador realizar esa prueba.
