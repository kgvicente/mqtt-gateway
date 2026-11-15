# Manual Operativo y Guía de Integración: MQTT Auth & ACL Gateway

> [!NOTE]
> **Aviso Legal / Trademark Disclaimer:** Todas las marcas comerciales, marcas de servicio, nombres de productos y logotipos mencionados en este documento (incluidos EMQX, Mosquitto, VerneMQ y HiveMQ) son propiedad de sus respectivos dueños. Este proyecto es una herramienta comunitaria independiente distribuida bajo la licencia GNU Affero General Public License v3.0 (AGPL-3.0).

> [!IMPORTANT]
> **Única fuente de verdad:** El código fuente del repositorio ([AdminResource.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/AdminResource.java), [GatewayState.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/GatewayState.java), [StorageService.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/StorageService.java), [GatewayConfig.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/GatewayConfig.java), [application.yml](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/resources/application.yml)) es la máxima autoridad técnica y contractual del sistema. Este manual describe su integración y procedimientos operativos.

Este documento proporciona las guías completas para desarrolladores de backends y administradores del sistema para operar, registrar nuevos servicios y monitorear el microservicio **MQTT Gateway** desplegado en tu servidor.

---

## Módulo 1: Guía de Integración para Desarrolladores de Backends

Cualquier servicio o microservicio backend (ej. *IoT Core Service*, *Device Gateway*, *Telemetry Service*, *Alerts Service*) que necesite interactuar a través de un bróker MQTT (EMQX, Mosquitto, VerneMQ, etc.) debe exponer dos endpoints HTTP REST en su propio backend.

### 1.1. Convenciones de Nomenclatura (Namespaces)

Para que el Gateway pueda enrutar el tráfico al backend correcto sin colisiones entre sistemas, cada servicio debe adoptar un **prefijo de cliente** y un **prefijo de tópico**:

| Sistema / Servicio | Prefijo de ClientID | Prefijo de Tópico | Ejemplo ClientID | Ejemplo Tópico |
| :--- | :--- | :--- | :--- | :--- |
| **IoT Core Service** | `core_`, `android_`, `ios_`, `web_` | `v1/`, `core/` | `core_71234567` | `v1/1/123/ubicacion` |
| **Device Gateway** | `dev_` | `dev/` | `dev_device_01` | `dev/devices/alert` |
| **Telemetry Service** | `tel_` | `tel/` | `tel_sensor_89` | `tel/metrics/flow` |
| **Nuevo Servicio** | `<prefijo>_` | `<prefijo>/` | `alert_user_45` | `alert/notifications/box1` |

---

### 1.2. Especificación Técnica: Endpoint de Autenticación (`/auth`)

Invocado cuando un dispositivo o usuario intenta conectarse al bróker MQTT.

* **Método:** `POST`
* **Ruta recomendada:** `/api/v1/emqx/auth`
* **Cabecera opcional:** `X-EMQX-API-KEY: <tu-clave-secreta>` (si tu backend la requiere para protección).

#### Payload que recibe tu backend (Request JSON):
```json
{
  "clientid": "citas_user_45",
  "username": "45678912",
  "password": "token_o_password_plana",
  "ipaddress": "190.237.15.4",
  "protocol": "MQTT"
}
```

#### Respuesta que debe retornar tu backend (Response JSON):
> [!IMPORTANT]
> Tu backend **siempre debe responder con HTTP Status 200 OK** y un payload JSON indicando el resultado. No devuelvas códigos 401 ni 403.

* **Si las credenciales son válidas:**
  ```json
  {
    "result": "allow",
    "is_superuser": false
  }
  ```
* **Si las credenciales son incorrectas:**
  ```json
  {
    "result": "deny"
  }
  ```
* **Si el usuario no pertenece a tu sistema:**
  ```json
  {
    "result": "ignore"
  }
  ```

---

### 1.3. Especificación Técnica: Endpoint de Autorización ACL (`/acl`)

Invocado cada vez que un cliente intenta suscribirse (`subscribe`) o publicar un mensaje (`publish`) en un tópico.

* **Método:** `POST`
* **Ruta recomendada:** `/api/v1/emqx/acl`
* **Cabecera opcional:** `X-EMQX-API-KEY: <tu-clave-secreta>`

#### Payload que recibe tu backend (Request JSON):
```json
{
  "clientid": "citas_user_45",
  "username": "45678912",
  "action": "publish",
  "topic": "citas/turnos/box1",
  "ipaddress": "190.237.15.4",
  "qos": 1
}
```

#### Respuesta que debe retornar tu backend (Response JSON):
* **Si tiene permiso para publicar o suscribirse:**
  ```json
  {
    "result": "allow"
  }
  ```
* **Si NO tiene permiso:**
  ```json
  {
    "result": "deny"
  }
  ```

---

### 1.4. Ejemplo de Código para Backends

#### En Spring Boot (Java):
```java
@RestController
@RequestMapping("/api/v1/emqx")
public class EmqxIntegrationController {

    @Value("${emqx.api-key:mi-clave}")
    private String expectedApiKey;

    @PostMapping("/auth")
    public ResponseEntity<Map<String, Object>> auth(
            @RequestHeader(value = "X-EMQX-API-KEY", required = false) String apiKey,
            @RequestBody Map<String, Object> body) {

        // 1. Validar clave de servicio interno
        if (!expectedApiKey.equals(apiKey)) {
            return ResponseEntity.ok(Map.of("result", "deny"));
        }

        String username = (String) body.get("username");
        String password = (String) body.get("password"); // Puede ser JWT o password

        // 2. Lógica propia del sistema (validar en BD o JWT)
        boolean valid = miServicioDeAuth.validar(username, password);

        return ResponseEntity.ok(Map.of(
            "result", valid ? "allow" : "deny",
            "is_superuser", false
        ));
    }

    @PostMapping("/acl")
    public ResponseEntity<Map<String, Object>> acl(@RequestBody Map<String, Object> body) {
        String topic = (String) body.get("topic");
        String action = (String) body.get("action");
        String username = (String) body.get("username");

        boolean permitido = miServicioAcl.puedeAcceder(username, topic, action);
        return ResponseEntity.ok(Map.of("result", permitido ? "allow" : "deny"));
    }
}
```

#### En Node.js (Express / Fastify):
```javascript
app.post('/api/v1/emqx/auth', (req, res) => {
    const { username, password, clientid } = req.body;
    const esValido = validarUsuario(username, password);

    return res.status(200).json({
        result: esValido ? 'allow' : 'deny',
        is_superuser: false
    });
});

app.post('/api/v1/emqx/acl', (req, res) => {
    const { username, topic, action } = req.body;
    const tienePermiso = verificarPermiso(username, topic, action);

    return res.status(200).json({
        result: tienePermiso ? 'allow' : 'deny'
    });
});
```

---

## Módulo 2: Guía para Administradores: Registrar Nuevos Sistemas en el Gateway

Cuando un nuevo servicio o microservicio esté listo para conectarse al bróker, el administrador del Gateway solo debe registrar sus rutas.

### 2.1. Archivo de Configuración de Rutas y Persistencia

El archivo de configuración inicial reside en:
`src/main/resources/application.yml`

Las rutas se almacenan de forma operativa en una base de datos SQLite embebida bajo `data/gateway.db` (en Docker `/app/data/gateway.db`), operando en modo WAL. Las rutas definidas en YAML se utilizan para sembrar la base de datos únicamente en arranques limpios iniciales; una vez inicializada, SQLite es la fuente de verdad y los cambios en YAML no sobrescriben las rutas existentes.

> [!IMPORTANT]
> **Secretos estrictamente externos:** La base de datos nunca almacena claves directas en texto plano. En el YAML se define `api-key-env` con el nombre de la variable de entorno del proceso que contiene el secreto.

```yaml
gateway:
  data-dir: ${GATEWAY_DATA_DIR:data}
  storage:
    legacy-api-key-env:
      core-service: EMQX_BACKEND_API_KEY
  admin:
    auth-provider: ${GATEWAY_ADMIN_AUTH_PROVIDER:internal}
    username: ${GATEWAY_ADMIN_USERNAME:admin}
    password: ${GATEWAY_ADMIN_PASSWORD:admin-secret-2026$}
  superuser:
    username: ${EMQX_SUPERUSER_USERNAME:backend-system-client}
    password: ${EMQX_SUPERUSER_PASSWORD:backend-system-password-2026$}
  timeout-ms: 2000
  global-topics: [heart, ping]

  # Sembrado inicial de monitoreo (solo primer arranque limpio sin SQLite previa)
  monitoring:
    probe-interval-seconds: 30
    probe-timeout-ms: 2000
    failure-threshold: 3
    recovery-threshold: 2

  # Configuración de notificaciones por Telegram (token estrictamente de entorno)
  telegram:
    bot-token: ${GATEWAY_TELEGRAM_BOT_TOKEN:}

  # 1. Rutas de Autenticación (enrutan por prefijo de clientid)
  auth:
    - prefixes: [core_, android_, ios_, web_]
      url: http://127.0.0.1:8090/api/v1/emqx/auth
      api-key-env: ${EMQX_BACKEND_API_KEY_ENV:}

    - prefixes: [dev_]
      url: http://127.0.0.1:8091/api/v1/emqx/auth

    - prefixes: [tel_]
      url: http://127.0.0.1:8080/api/v1/emqx/auth

  # 2. Rutas de Autorización ACL (enrutan por prefijo de topic)
  acl:
    - prefixes: [v1/, core/]
      url: http://127.0.0.1:8090/api/v1/emqx/acl
      api-key-env: ${EMQX_BACKEND_API_KEY_ENV:}

    - prefixes: [dev/]
      url: http://127.0.0.1:8091/api/v1/emqx/acl

    - prefixes: [tel/]
      url: http://127.0.0.1:8080/api/v1/emqx/acl
```

### 2.2. Paso a Paso para Agregar un Sistema (Ejemplo: *Alerts Service*)

Supongamos que el servicio *Alerts Service* corre en `http://127.0.0.1:8082` y usará los prefijos `alerts_` y `alerts/`:

1. **Editar el archivo `application.yml`:**
   ```bash
   nano src/main/resources/application.yml
   ```
2. **Agregar la entrada en `auth` y en `acl`:**
   ```yaml
     auth:
       # ... sistemas existentes ...
       - prefixes: [alerts_]
         url: http://127.0.0.1:8082/api/v1/emqx/auth
         api-key-env: ALERTS_API_KEY

     acl:
       # ... sistemas existentes ...
       - prefixes: [alerts/]
         url: http://127.0.0.1:8082/api/v1/emqx/acl
         api-key-env: ALERTS_API_KEY
   ```
   *(Nota: También puedes registrar o editar el backend directamente desde el panel administrativo web en caliente sin reiniciar).*
3. **Recompilar el binario nativo:**
   ```bash
   ./mvnw package -Dnative -Dquarkus.native.container-build=true
   ```
4. **Reiniciar el servicio:**
   ```bash
   systemctl --user restart mqtt-gateway
   ```

---

## Módulo 3: Manual de Administración y Operaciones del Servicio

El servicio se ejecuta de forma nativa en Linux bajo la gestión de **`systemd --user`** (o como servicio del sistema con `mqtt-gateway.service`).

### 3.1. Comandos de Gestión del Servicio

| Acción | Comando |
| :--- | :--- |
| **Ver estado en vivo** | `systemctl --user status mqtt-gateway` |
| **Reiniciar el servicio** | `systemctl --user restart mqtt-gateway` |
| **Detener el servicio** | `systemctl --user stop mqtt-gateway` |
| **Iniciar el servicio** | `systemctl --user start mqtt-gateway` |
| **Habilitar arranque con el sistema** | `systemctl --user enable mqtt-gateway` |

---

### 3.2. Monitoreo de Logs en Tiempo Real

Para ver los registros de peticiones, errores o actividad en tiempo real:

```bash
# Ver los logs en vivo (equivalente a tail -f)
journalctl --user -u mqtt-gateway -f

# Ver las últimas 50 líneas de log
journalctl --user -u mqtt-gateway -n 50 --no-pager

# Ver logs de la última hora
journalctl --user -u mqtt-gateway --since "1 hour ago"
```

---

### 3.3. Comprobación Rápida de Salud (Health Check)

Puedes verificar si el Gateway está activo y respondiendo consultas con:

```bash
curl -i http://127.0.0.1:8085/q/health
```

Respuesta esperada:
```json
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
    "status": "UP",
    "checks": []
}
```

---

### 3.4. Dónde se configuran las variables de entorno

Las variables de entorno estructurales del proceso se definen en el archivo de servicio o entorno de despliegue. A partir de la versión V4, los secretos de negocio (claves API de backend y token de bot de Telegram) se gestionan directamente desde el panel web y se almacenan cifrados con AES-256-GCM en la base de datos SQLite. El proceso requiere la clave maestra de infraestructura `GATEWAY_MASTER_KEY` para descifrarlos en memoria:

`~/.config/systemd/user/mqtt-gateway.service`:
```ini
[Service]
# Clave maestra de cifrado para secretos en reposo (Base64 estándar de 32 bytes aleatorios):
Environment=GATEWAY_MASTER_KEY=REEMPLAZAR_POR_CLAVE_BASE64_32_BYTES

# Credenciales administrativas y configuración de transporte:
Environment=EMQX_SUPERUSER_USERNAME=backend-system-client
Environment=EMQX_SUPERUSER_PASSWORD=backend-system-password-2026$
Environment=GATEWAY_ADMIN_AUTH_PROVIDER=internal
Environment=GATEWAY_ADMIN_USERNAME=admin
Environment=GATEWAY_ADMIN_PASSWORD=admin-secret-2026$
Environment=GATEWAY_DATA_DIR=%h/.local/share/mqtt-gateway/data

# Opcionales: sembrado inicial de monitoreo (solo surten efecto en el primer arranque limpio sin SQLite previa):
# Environment=GATEWAY_MONITORING_PROBE_INTERVAL_SECONDS=30
# Environment=GATEWAY_MONITORING_PROBE_TIMEOUT_MS=2000
# Environment=GATEWAY_MONITORING_FAILURE_THRESHOLD=3
# Environment=GATEWAY_MONITORING_RECOVERY_THRESHOLD=2
```

Tras modificar el archivo, aplica los cambios con:
```bash
systemctl --user daemon-reload
systemctl --user restart mqtt-gateway
```

---

### 3.5. Almacenamiento SQLite, Secretos Cifrados y Copias de Seguridad

1. **Almacenamiento Operativo SQLite**:
   * El estado persistente (rutas, incidentes, configuraciones, outbox, checkpoints y secretos cifrados) se gestiona en una base de datos SQLite bajo `data/gateway.db` (en contenedor Docker: `/app/data/gateway.db`).
   * Opera en modo WAL (`Write-Ahead Logging`), con `foreign_keys=ON`, `busy_timeout=5000` y `synchronous=FULL`.
   * El esquema de la base de datos se actualiza automáticamente mediante migraciones transaccionales versionadas con checksum SHA-256 (`schema_migrations`).

2. **Cifrado de Secretos y Clave Maestra**:
   * Los secretos (`apiKey` de backend y `botToken` de Telegram) se almacenan cifrados con **AES-256-GCM** mediante JCA estándar (`AES/GCM/NoPadding`), con nonces aleatorios de 12 bytes por cifrado y AAD por versión y propietario.
   * `GATEWAY_MASTER_KEY` debe mantenerse fuera de la base de datos y respaldarse por separado en un gestor seguro de credenciales. Si se pierde la clave maestra, los secretos no podrán ser descifrados.
   * Si la clave maestra cambia sin recifrar o no coincide con los datos existentes, el canary criptográfico detecta la discrepancia e impide el arranque. No se ofrece rotación automática en caliente desde la interfaz.
   * Si se requiere rollback a un binario anterior (V1-V3), un binario antiguo no reconoce el esquema de versión V4; se requiere restaurar el respaldo de la base de datos previo a la migración.

3. **Migración de Secretos Legados**:
   * Al actualizar a V4, si existen referencias previas a variables de entorno (`apiKeyEnv`), el servicio las resuelve en el arranque y las migra de forma atómica y por única vez a la tabla de secretos cifrados en SQLite, limpiando las referencias de variables de entorno persistidas.

4. **Procedimiento de Respaldo y Restauración (Backup & Restore)**:
   > [!CAUTION]
   > Para garantizar la integridad de SQLite WAL y prevenir corrupción, ejecute respaldos y restauraciones exclusivamente con el servicio detenido, asegúrese de extraer la restauración en un directorio completamente vacío con permisos 700 para no mezclar archivos WAL/SHM residuales ajenos, y recuerde que el servicio vuelve al estado del respaldo mientras los cambios posteriores quedan preservados en la copia fechada de seguridad. Respalde la clave `GATEWAY_MASTER_KEY` por separado fuera del archivo comprimido de la base de datos.

   * **Para Respaldar (Backup):**
     Utiliza la ruta de datos configurada explícita en el servicio (evitando rutas relativas ambiguas si el servicio systemd define `GATEWAY_DATA_DIR=%h/.local/share/mqtt-gateway/data`, o la ruta absoluta `./data` en ejecución local):
     ```bash
     set -e

     # 1. Definir la ruta de datos configurada explícita
     DATA_DIR="${GATEWAY_DATA_DIR:-$HOME/.local/share/mqtt-gateway/data}"

     # 2. Aplicar umask restrictivo para proteger los datos
     umask 077

     # 3. Detener el servicio para vaciar y consolidar checkpoints de WAL
     systemctl --user stop mqtt-gateway

     # 4. Respaldar el directorio de datos completo
     tar -czf "gateway-backup-$(date +%Y%m%d%H%M%S).tar.gz" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")"

     # 5. Iniciar el servicio
     systemctl --user start mqtt-gateway
     ```

   * **Para Restaurar (Restore):**
     Nunca extraiga sobre un directorio con datos previos. Mueva el directorio actual a un respaldo fechado de seguridad, cree un directorio completamente vacío con permisos restrictivos y extraiga el respaldo con `--strip-components=1` dentro de él:
     ```bash
     set -e

     # 1. Definir la ruta explícita y el archivo de respaldo
     DATA_DIR="${GATEWAY_DATA_DIR:-$HOME/.local/share/mqtt-gateway/data}"
     BACKUP_FILE="/ruta/hacia/gateway-backup-YYYYMMDDHHMMSS.tar.gz"

     # 2. Aplicar umask restrictivo
     umask 077

     # 3. Detener el servicio
     systemctl --user stop mqtt-gateway

     # 4. Mover el directorio actual a un respaldo fechado de seguridad (evita residuos de WAL/SHM ajenos)
     if [ -d "$DATA_DIR" ]; then
         mv "$DATA_DIR" "${DATA_DIR}.pre-restore-$(date +%Y%m%d%H%M%S)"
     fi

     # 5. Crear un directorio completamente VACÍO con modo 700
     mkdir -m 700 -p "$DATA_DIR"

     # 6. Extraer el contenido dentro del directorio vacío eliminando el prefijo raíz
     tar -xzf "$BACKUP_FILE" --strip-components=1 -C "$DATA_DIR"

     # 7. Iniciar el servicio
     systemctl --user start mqtt-gateway
     ```
     *(Nota: El gateway vuelve al estado exacto del respaldo restaurado; cualquier cambio, incidente o métrica posterior a esa fecha queda resguardado en la copia fechada `${DATA_DIR}.pre-restore-*`).*

---

### 3.6. Monitoreo Configurable y Política de Incidentes

El sistema permite configurar la frecuencia y sensibilidad del monitoreo de salud tanto a nivel global como por backend individual, aplicando cambios en caliente sin reiniciar el servicio.

1. **Parámetros de Monitoreo**:
   - `probeIntervalSeconds`: Frecuencia de sondeo en segundos (1 a 86 400 s, por defecto 30 s). El siguiente sondeo comienza cuando termina el anterior y transcurre este intervalo.
   - `probeTimeoutMs`: Timeout máximo de espera por sondeo (100 a 60 000 ms, por defecto 2 000 ms). Debe ser menor al intervalo de sondeo en milisegundos.
   - `failureThreshold`: Número de sondeos fallidos consecutivos (timeouts, caídas de red o HTTP >= 500) requeridos para confirmar una caída y abrir un incidente (1 a 100, por defecto 3).
   - `recoveryThreshold`: Número de sondeos exitosos consecutivos (HTTP < 500) requeridos para confirmar la recuperación y cerrar el incidente activo (1 a 100, por defecto 2).

2. **Herencia y Overrides por Backend**:
   - Los backends heredan por defecto los valores globales del sistema.
   - Es posible asignar overrides independientes a cualquier backend desde el panel web o mediante `PUT /api/admin/routes/{id}`. Los campos con valor `null` eliminan el override específico y restablecen la herencia del valor global.
   - La API expone siempre `effectiveMonitoring` con los 4 valores finales resueltos.

3. **Estimación Aproximada del Tiempo de Detección**:
   - **Tiempo para detectar caída:** Entre `(failureThreshold - 1) * probeIntervalSeconds` y `failureThreshold * (probeIntervalSeconds + probeTimeoutMs / 1000)`. Con valores por defecto (30 s / 2 000 ms / 3 fallos), la caída se confirma en un rango aproximado de **60 a 96 segundos**.
   - **Tiempo para confirmar recuperación:** Entre `(recoveryThreshold - 1) * probeIntervalSeconds` y `recoveryThreshold * (probeIntervalSeconds + probeTimeoutMs / 1000)`. Con valores por defecto (30 s / 2 000 ms / 2 éxitos), la recuperación se confirma en un rango aproximado de **30 a 64 segundos**.

4. **Sondeos Manuales vs Automáticos**:
   - Los sondeos automáticos periódicos son los **únicos** que alteran los contadores consecutivos, computan el uptime y abren/cierran incidentes.
   - El botón **Probar conexión** (`POST /api/admin/routes/{id}/test`) ejecuta un sondeo aislado puntual. Si el slot de sondeo del backend está ocupado por una comprobación en curso, la API responde con **HTTP 409 (Conflict)** para evitar solapamientos.

5. **Sembrado Inicial vs Precedencia de SQLite**:
   - Los valores por defecto de arranque pueden definirse en `application.yml` (`gateway.monitoring.*`) o variables de entorno (`GATEWAY_MONITORING_*`).
   - Estos valores se usan **únicamente para el sembrado inicial** cuando el servicio inicia por primera vez en un directorio limpio sin base de datos SQLite preexistente.
   - Una vez creada e inicializada la base de datos, la configuración guardada en SQLite tiene **prioridad absoluta en los reinicios**. Para modificar los parámetros posteriormente, hágalo en caliente vía panel web o API REST (`PUT /api/admin/settings`); modificar las variables de entorno o el archivo YAML posterior al sembrado no sobrescribirá los ajustes almacenados en SQLite.

---

### 3.7. Notificaciones de Incidentes por Telegram y Configuración de Alertas

El Gateway incorpora un sistema transaccional de notificaciones en tiempo real vía Telegram para alertar automáticamente a los equipos de operaciones sobre aperturas y recuperaciones de incidentes de salud en los backends configurados.

#### 1. Arquitectura de Seguridad y Token Externo
* **Token estrictamente fuera de la base de datos:** El token del bot nunca se persiste en SQLite, ni se expone en la interfaz web ni en respuestas de la API. Se suministra exclusivamente al proceso mediante la variable de entorno `GATEWAY_TELEGRAM_BOT_TOKEN` (o `gateway.telegram.bot-token` en `application.yml`).
* **Indicador `tokenConfigured`:** La API administrativa y el panel web exponen únicamente un valor booleano `tokenConfigured`. Si este valor es `false`, la emisión de alertas queda suspendida y la UI presenta un aviso explicativo con instrucciones para inyectar la variable en el entorno. Este indicador comprueba estrictamente la presencia de la variable en el proceso; no valida la autenticidad del token ante Telegram ni los permisos del bot en el chat.
* **Precondiciones para activación:** Para activar el interruptor maestro (`enabled: true`), el backend valida que `tokenConfigured` sea `true` y que exista un `chatId` global válido (de lo contrario responde `HTTP 400 Bad Request`).
* **Despliegue en Contenedores:** Para la guía detallada de configuración segura bajo Docker Compose sin exponer secretos en el historial ni modificar archivos base, consulta la [Guía de Telegram en Docker Compose](docs/telegram-docker.es.md). Para entender en profundidad las garantías y el diseño arquitectónico de las tres fases, consulta el documento [Las tres fases del gateway](docs/three-phases.es.md).

#### 2. Jerarquía de Configuración y Herencia por Backend
* **Configuración Global (`/api/admin/settings`):**
  Define el interruptor maestro general (`enabled`) y el destino de fallback (`chatId`). Omitir la sección `telegram` en peticiones `PUT` conserva los valores existentes sin sobrescribirlos.
* **Configuración por Backend (`/api/admin/routes/{id}`):**
  Cada ruta expone un bloque opcional `telegram: { enabled, chatId }` y devuelve siempre `effectiveTelegram: { enabled, chatId }` con los valores resueltos:
  - `enabled: null` (Herencia): Participa en las alertas si el interruptor maestro global está encendido.
  - `enabled: false` (Exclusión): Se excluye explícitamente de las notificaciones, incluso si el global está activo.
  - `enabled: true`: Habilitado explícitamente (requiere que el maestro global esté activo).
  - `chatId: null`: Hereda el destino global. Si se proporciona un `chatId` específico, las alertas de ese backend se enrutan exclusivamente a ese canal o grupo.

#### 3. Puesta en Marcha Paso a Paso con @BotFather y Chat ID
1. **Crear el Bot:** Abra Telegram, busque `@BotFather`, envíe `/newbot` y siga las instrucciones interactivas para obtener el token secreto del bot.
2. **Configurar el Entorno:** Inyecte la variable `GATEWAY_TELEGRAM_BOT_TOKEN` en la configuración de `systemd` o Docker Compose. Nunca la guarde en scripts versionados, ni en `application.yml` (que solo debe contener la referencia `${GATEWAY_TELEGRAM_BOT_TOKEN:}`), ni en el historial de shell.
3. **Iniciar la Conversación con el Bot:**
   - Para un chat individual: Busque a su bot en Telegram y pulse **Iniciar** (`/start`).
   - Para un grupo o canal: Añada al bot como miembro (con permisos de envío de mensajes) y envíe un mensaje al grupo.
4. **Obtener el Chat ID de Forma Segura:**
   - Para no exponer el token en argumentos de procesos, historial ni mensajes de error, ejecute una consulta que lea la credencial directamente desde el entorno:
     ```bash
     python3 -c '
     import os, json, urllib.request
     token = os.environ.get("GATEWAY_TELEGRAM_BOT_TOKEN", "").strip()
     if not token:
         raise SystemExit("Error: GATEWAY_TELEGRAM_BOT_TOKEN no configurada en el entorno.")
     try:
         req = urllib.request.Request(f"https://api.telegram.org/bot{token}/getUpdates")
         with urllib.request.urlopen(req, timeout=10) as resp:
             data = json.loads(resp.read().decode())
             chats = {str(item["message"]["chat"]["id"]): item["message"]["chat"].get("title") or item["message"]["chat"].get("username")
                      for item in data.get("result", []) if "message" in item and "chat" in item["message"]}
             print("Chats detectados:", json.dumps(chats, indent=2) if chats else "Sin mensajes recientes. Envia un mensaje al bot y reintenta.")
     except Exception:
         print("Error de comunicacion con la API de Telegram. Verifica conectividad y validez de la credencial en el entorno.")
     '
     ```
   - Localice el objeto `"chat":{"id": ...}` correspondiente. Los chats individuales son enteros positivos (ej. `123456789`), mientras que los grupos y supergrupos suelen comenzar con prefijos negativos (ej. `-1001234567890`).
5. **Activar en el Gateway:** Diríjase al panel web (`/gateway/`), abra **Ajustes globales**, ingrese el Chat ID en el campo de Telegram, active la casilla y guarde los cambios.

#### 4. Garantías de Entrega y Ciclo de Vida (Transactional Outbox)
* **Semántica At-Least-Once y Ausencia de Entrega Absoluta:** Cuando un backend abre o cierra un incidente, la notificación se inserta en la misma transacción SQLite que el registro del incidente. Un worker en segundo plano procesa la cola outbox de forma desacoplada. Ante particiones de red o reinicios abruptos durante el acuse HTTP de Telegram, pueden despacharse mensajes duplicados puntuales. **No existe garantía absoluta de entrega en todos los escenarios**: las notificaciones pueden ser descartadas por saturación de cola (límite de 10 000 eventos en estado `not_enqueued`), canceladas administrativamente si el operador pausa o elimina la ruta (`cancelled`), marcadas como fallidas ante respuestas permanentes de cliente (`HTTP 4xx`), o expirar tras 24 horas continuadas de indisponibilidad de red (`expired`).
* **Límites de Rate Limit y Reintentos:** El despachador respeta el límite estricto de Telegram (mínimo 3 segundos entre mensajes hacia un mismo chat, timeout HTTP de 5 s por llamada, **5 intentos totales: 1 envío inicial + hasta 4 reintentos** con backoff exponencial y jitter, y respeto del parámetro JSON `parameters.retry_after` ante respuestas HTTP 429).
* **Retención y Expiración:** Capacidad máxima de la cola: 10 000 notificaciones. Mensajes no entregados tras 24 horas pasan a estado `expired`. Los registros del historial se retienen durante 30 días.
* **Reglas de Cancelación:**
  - Desactivar Telegram a nivel global (`enabled: false`) cancela inmediatamente todas las notificaciones pendientes en cola.
  - Pausar o eliminar una ruta cancela sus notificaciones pendientes asociadas y no despacha mensajes de recuperación.
  - Modificar el destino (`chatId`) de una ruta o global no redirige notificaciones que ya estén encoladas pendientes con el destino anterior.
  - Una notificación aceptada exitosamente por la API de Telegram no se puede cancelar ni deshacer.

#### 5. Pruebas de Notificación sin Riesgo
* **Acción de Prueba (`POST /api/admin/notifications/test`):**
  Disponible en el panel web bajo la pestaña *Notificaciones* -> *Enviar prueba*, o mediante llamada REST enviando `{ "backendId": "<id-opcional>" }`.
* **Semántica HTTP 202 (Accepted):**
  La API devuelve inmediatamente `HTTP 202` con `{ "id": "<uuid>", "status": "pending" }` o `{ "id": "<uuid>", "status": "not_enqueued" }`. El estado `not_enqueued` se devuelve si la cola ha alcanzado el límite de 10 000 eventos pendientes. El código 202 confirma que la solicitud ha sido aceptada y procesada por el outbox para su envío en segundo plano, no que haya sido entregada instantáneamente a Telegram.
* **Seguridad de Destinos:** No se permite el envío de textos personalizados ni el ingreso de destinos arbitrarios no registrados; la prueba utiliza únicamente la configuración efectiva de Telegram ya resuelta.

---

## Módulo 4: Solución de Problemas Frecuentes (Troubleshooting)

1. **El bróker reporta `authentication_failure` o `not_authorized`:**
   * **Causa 1:** El `clientid` del dispositivo no empieza con ninguno de los prefijos configurados (`core_`, `android_`, `ios_`, `dev_`, `tel_`). En este caso, el Gateway devuelve `{"result": "ignore"}` y el bróker rechaza la conexión.
   * **Causa 2:** El backend de destino está caído o superó el timeout de 2000 ms. Revisa los logs con `journalctl --user -u mqtt-gateway -f`.
2. **El backend recibe la petición pero devuelve 403:**
   * Verifica que la clave API configurada para el backend desde el panel web (o mediante la API administrativa) coincida exactamente con la cabecera `X-EMQX-API-KEY` esperada por tu servicio backend.
3. **El puerto 8085 no responde:**
   * Ejecuta `ss -tulpn | grep 8085` para confirmar si el binario está escuchando.
   * Si está detenido, revisa las causas de caída con `journalctl --user -u mqtt-gateway -n 30`.
