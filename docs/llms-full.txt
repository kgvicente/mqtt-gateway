# Referencia técnica de MQTT Gateway

[Presentación](https://github.com/kgvicente/mqtt-gateway/blob/main/README.es.md) | [Inicio rápido](https://github.com/kgvicente/mqtt-gateway/blob/main/docs/quickstart.es.md) | [English overview](https://github.com/kgvicente/mqtt-gateway/blob/main/README.md)

> [!IMPORTANT]
> **Única fuente de verdad del contrato:** El código fuente del repositorio es la máxima autoridad técnica y contractual del gateway ([AdminResource.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/AdminResource.java), [GatewayState.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/GatewayState.java), [StorageService.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/StorageService.java), [GatewayConfig.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/GatewayConfig.java) y [application.yml](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/resources/application.yml)). Los planes (`docs/managed-secrets-plan.es.md`, `docs/implementation-plan.es.md`) e informes de auditoría (`docs/documentation-audit.es.md`) constituyen registros históricos del proceso de diseño e implementación y no poseen autoridad normativa frente al código fuente.

Consulta contratos, configuración, métricas y operación. Para probar el proyecto
por primera vez, empieza por la guía de inicio rápido.

## Ejecutar y comprobar

Para la demo con un bróker real y contenedores, consulta [Docker Compose](https://github.com/kgvicente/mqtt-gateway/blob/main/docs/compose.es.md).

```sh
./mvnw package
python3 scripts/smoke.py
./mvnw package -Dnative -Dquarkus.native.container-build=true
python3 scripts/smoke.py ./target/mqtt-gateway-0.2.0-runner
```

JDK 25 es necesario para compilar. Docker solo es necesario para la modalidad
de compilación nativa en contenedor. Maven Wrapper descarga Maven.
El ejecutable Linux queda en target/mqtt-gateway-0.2.0-runner.
Si el ejecutable ya está en uso, renombrarlo antes de compilar para conservar
el proceso activo y disponer de una copia de recuperación.

Para generar la distribución JVM y su suma SHA-256, con Python 3.11 o posterior:

```sh
./mvnw package
python3 scripts/package_release.py
```

Los archivos quedan en `target/releases/`. El ZIP contiene el directorio
`quarkus-app` completo, instrucciones y licencias. Necesita JRE/JDK 25 en Linux
o WSL; no incluye un runtime Java ni un bróker MQTT.

Los smoke usan EMQX y backends simulados, puerto 18085 y datos temporales en
target/. Comprueban los dos proveedores administrativos, los cuatro adaptadores,
rutas, API keys, SSO, autorización, CRUD, pausa, métricas,
incidentes, sondeo, recuperación, persistencia, reinicio y auditoría.

Pruebas de navegador opcionales, sin dependencias en producción:

```sh
npm install --prefix target/browser --no-package-lock playwright@1.55.1
target/browser/node_modules/.bin/playwright install chromium
UI_SMOKE=1 python3 scripts/smoke.py
UI_SMOKE=1 python3 scripts/smoke.py ./target/mqtt-gateway-0.2.0-runner
```

Capturas de escritorio y móvil: target/admin-desktop.png y target/admin-mobile.png.
Las pruebas incluyen localStorage, sessionStorage, fragmento, iframe/postMessage,
login, CRUD, pestañas y viewport móvil.

## Configuración

La escucha por defecto es 127.0.0.1:8085. Los valores iniciales de rutas y
superusuario están en src/main/resources/application.yml.

| Variable | Valor por defecto | Uso |
| --- | --- | --- |
| GATEWAY_ADMIN_AUTH_PROVIDER | internal | Proveedor auth panel (`internal` o `emqx`) |
| GATEWAY_ADMIN_USERNAME | admin | Usuario administrativo local |
| GATEWAY_ADMIN_PASSWORD | admin-secret-2026$ | Contraseña administrativa local |
| EMQX_SUPERUSER_USERNAME | backend-system-client | Superusuario Auth |
| EMQX_SUPERUSER_PASSWORD | backend-system-password-2026$ | Password del superusuario |
| EMQX_BACKEND_API_KEY_ENV | Vacío | Nombre de variable de entorno para la API key de la ruta inicial |
| EMQX_BACKEND_API_KEY | Vacío | Secreto externo de ejemplo para la ruta inicial |
| GATEWAY_DATA_DIR | data | Directorio de estado |
| GATEWAY_EMQX_URL | http://127.0.0.1:18083 | Dashboard EMQX (si se usa modo emqx) |
| GATEWAY_MASTER_KEY | (Obligatoria) | Clave maestra Base64 de 32 bytes para cifrado AES-256-GCM de secretos en SQLite |
| GATEWAY_PROBE_SECONDS | 30 | Intervalo de sondeo (legado / fallback inicial) |
| GATEWAY_TIMEOUT_MS | 2000 | Timeout de backends y EMQX (fallback inicial de sondeo) |
| GATEWAY_MONITORING_PROBE_INTERVAL_SECONDS | 30 | Sembrado inicial: intervalo de sondeo global en segundos |
| GATEWAY_MONITORING_PROBE_TIMEOUT_MS | 2000 | Sembrado inicial: timeout de sondeo global en milisegundos |
| GATEWAY_MONITORING_FAILURE_THRESHOLD | 3 | Sembrado inicial: fallos consecutivos para confirmar caída |
| GATEWAY_MONITORING_RECOVERY_THRESHOLD | 2 | Sembrado inicial: éxitos consecutivos para confirmar recuperación |

El estado se almacena en SQLite bajo `data/gateway.db` (en Docker `/app/data/gateway.db`), operando en modo WAL (`foreign_keys=ON`, `busy_timeout=5000`, `synchronous=FULL`). Las migraciones SQL son transaccionales y versionadas en `schema_migrations` con suma de comprobación SHA-256; versiones desconocidas o checksums inválidos impiden el arranque. Si existen archivos legados (`routes.json`, `incidents.json`, `daily-state.json`), se importan de forma atómica en una única transacción; cualquier archivo malformado aborta el arranque sin sembrar datos vacíos ni sobrescribir. Las rutas iniciales se siembran desde YAML únicamente en arranques limpios sin base de datos ni datos previos. Del mismo modo, las propiedades de monitoreo inicial (`gateway.monitoring.*` o sus variables `GATEWAY_MONITORING_*`) se emplean **únicamente para el sembrado inicial** cuando el gateway arranca en un directorio limpio sin base de datos SQLite previa (con fallback a `gateway.probe-seconds` y `gateway.timeout-ms` si no se especifican). Una vez inicializada la base de datos, la configuración guardada en SQLite tiene **prioridad absoluta en los reinicios**; modificaciones posteriores en archivos YAML o variables de entorno no sobrescriben los ajustes persistidos.

### Gestión de Secretos Cifrados desde el Panel (AES-256-GCM)

A partir de la versión V4, MQTT Gateway opera con un único modo de gestión de secretos:
* **Clave Maestra de Infraestructura (`GATEWAY_MASTER_KEY`)**: Variable de entorno obligatoria de exactamente 32 bytes aleatorios codificados en Base64 estándar. Se suministra exclusivamente en el arranque del contenedor o proceso. La base de datos SQLite **nunca almacena la clave maestra ni ningún derivado utilizable para descifrar**. Si la variable falta, está malformada o no coincide con los datos cifrados existentes (validado mediante un canary cifrado al iniciar), el gateway aborta el arranque con un mensaje de error saneado.
* **Cifrado Autenticado en Reposo**: Todos los secretos de negocio (`apiKey` de backend y `botToken` de Telegram) se almacenan cifrados con AES-256-GCM mediante JCA estándar (`AES/GCM/NoPadding`), con un nonce aleatorio de 12 bytes de `SecureRandom` por cifrado, etiqueta de autenticación de 128 bits y datos asociados autenticados (AAD) que ligan cada secreto a su versión y propietario (`backend:<id>` o `telegram:bot-token`).
* **Caché Privada en Memoria RAM**: Los secretos se descifran al inicio en una caché privada de proceso en RAM. Las peticiones MQTT (`/auth`, `/acl`) y los sondeos evalúan contra esta caché sin ejecutar consultas SQL ni operaciones de descifrado por llamada. La actualización de un secreto desde el panel web o API actualiza la memoria en caliente de inmediato sin reiniciar el servicio.
* **Contrato Write-Only para `routes.apiKey`**:
  - En peticiones `POST /api/admin/routes` y `PUT /api/admin/routes/{id}`, el campo `apiKey` es estrictamente *write-only*.
  - **Omitido en PUT**: Conserva el valor de clave actual sin alterarlo.
  - **Cadena vacía (`""`)**: Elimina explícitamente la clave API del backend (pasa a operar sin clave).
  - **Cadena no vacía**: Asigna o reemplaza la clave (máximo 4 096 caracteres; debe contener exclusivamente caracteres ASCII imprimibles sin caracteres de control, rango 0x20 a 0x7E, sin saltos de línea CRLF).
  - `null` u otros tipos de datos son rechazados con **HTTP 400**.
  - El campo obsoleto `apiKeyEnv` no se utiliza; enviar `apiKeyEnv` con valor no vacío es rechazado con **HTTP 400** indicando utilizar `apiKey`.
  - Las respuestas `GET /api/admin/routes` devuelven únicamente el booleano `hasApiKey: boolean` (true si existe clave configurada, false si no). Nunca exponen el texto de la clave ni ciphertext.
* **Migración única de referencias previas**: Si existían referencias `api_key_env` legadas, se resuelven y migran a secretos cifrados una sola vez de forma transaccional al actualizar a V4, limpiando las referencias de variables de entorno persistidas. No se mantiene un segundo modo operativo permanente.

### FAQ: Respaldo y restauración con servicio detenido

**¿Por qué el respaldo y la restauración deben realizarse con el servicio detenido y en directorios limpios?**
SQLite opera en modo WAL (*Write-Ahead Logging*). En este modo, las transacciones activas y recientes se registran primero en el archivo `gateway.db-wal`, auxiliado por el archivo de memoria compartida e índices `gateway.db-shm`. Si se realiza una copia en caliente de `gateway.db` mientras el proceso está escribiendo, se corre el riesgo de capturar una instantánea inconsistente o corrupta.
Detener el proceso garantiza que todos los cambios pendientes se consoliden (*checkpoint*) en el archivo principal `gateway.db` y se liberen todos los bloqueos de escritura (`busy_timeout=5000`).

Asimismo, al restaurar:
- **Peligro de residuos WAL/SHM ajenos:** Nunca extraiga un respaldo con `tar -xzf` encima de un directorio de datos existente con restos. Si el archivo de respaldo no incluye archivos WAL/SHM (o son de otra versión), los archivos `.db-wal` y `.db-shm` previos de la base de datos anterior permanecerán en disco, corrompiendo la restauración al reutilizar transacciones ajenas.
- **Procedimiento de restauración seguro:** Detenga el servicio con `set -e` y `umask 077`, utilice la ruta de datos configurada explícita (evitando rutas relativas ambiguas si el servicio systemd define `%h/.local/share/...`), mueva el directorio actual a un respaldo de seguridad fechado (`data.pre-restore-<timestamp>`), cree un directorio completamente **vacío** con modo `700` (`mkdir -m 700 -p`), extraiga el respaldo con `tar -xzf "$BACKUP_FILE" --strip-components=1 -C "$DATA_DIR"` dentro del directorio limpio y reinicie el servicio.
- **Alcance de la restauración (Rollback):** El gateway vuelve al estado exacto del respaldo restaurado; cualquier cambio, incidente o ruta posterior queda resguardado en la copia fechada previa sin perderse de forma irrecuperable.

## API del gateway

POST /api/v1/gateway/auth y POST /api/v1/gateway/acl conservan los contratos
EMQX y normalizan decisiones a HTTP 200 con result allow, deny o ignore.
allow añade is_superuser booleano. En estas rutas se conserva el JSON original del cliente.

Auth usa username/password para el superusuario y, en otros casos, el prefijo
de clientid. ACL permite heart y ping exactos y luego busca por prefijo de topic.
Se usa el prefijo activo más largo. Sin coincidencia devuelve ignore.
JSON inválido, clave de enrutamiento ausente, backend no 2xx, respuesta inválida,
timeout o fallo de conexión devuelve deny. Sin reintentos ni redirecciones.

Las rutas pausadas no reciben tráfico ni sondeos. Los cambios se aplican a las
nuevas peticiones; una petición en curso termina con su snapshot de ruta.
GET /q/health comprueba el proceso, no el estado de los backends.

## Administración y SSO

La SPA se sirve en /gateway/. Por defecto usa una cuenta interna configurada
y tokens en memoria de ocho horas. `GATEWAY_ADMIN_AUTH_PROVIDER=emqx` mantiene
la delegación a EMQX. Las dos modalidades se detallan más adelante.

| Método y endpoint | Operación |
| --- | --- |
| GET o POST /api/admin/auth/verify | Verificar token del proveedor configurado |
| POST /api/admin/auth/login | Validar credenciales con el proveedor configurado |
| GET /api/admin/settings | Consultar ajustes globales (monitoreo y Telegram) |
| PUT /api/admin/settings | Modificar ajustes globales de monitoreo y Telegram en caliente |
| GET /api/admin/routes | Listar rutas, sin claves, con effectiveMonitoring y effectiveTelegram |
| POST /api/admin/routes | Crear backend con monitoring y telegram opcionales |
| PUT /api/admin/routes/{id} | Reemplazar backend, omitir secciones para conservarlas |
| DELETE /api/admin/routes/{id} | Eliminar backend, conservar incidentes |
| POST /api/admin/routes/{id}/test | Ejecutar un sondeo manual aislado (409 si está ocupado) |
| GET /api/admin/metrics | Métricas por backend con contadores y estado de observación |
| GET /api/admin/notifications | Historial paginado de notificaciones y resumen de cola |
| POST /api/admin/notifications/test | Encolar notificación de prueba a Telegram (202 Accepted) |
| GET /api/admin/incidents | Historial persistente de incidentes |
| GET /api/admin/logs | Hasta 2000 eventos, más reciente primero |

PUT /api/admin/routes también acepta id en el cuerpo;
DELETE /api/admin/routes?id=... es equivalente a la variante con path.

### Ajustes globales y monitoreo configurable

La configuración global de sondeo se gestiona mediante `/api/admin/settings`:
```json
{
  "monitoring": {
    "probeIntervalSeconds": 30,
    "probeTimeoutMs": 2000,
    "failureThreshold": 3,
    "recoveryThreshold": 2
  }
}
```

- **Sembrado inicial y precedencia de SQLite**: Los valores iniciales pueden definirse en `application.yml` (`gateway.monitoring.*`) o variables de entorno (`GATEWAY_MONITORING_*`). Estos valores se utilizan **exclusivamente para el sembrado inicial** cuando el servicio arranca por primera vez sin una base de datos SQLite preexistente (si se omiten, toma fallback a `gateway.probe-seconds` y `gateway.timeout-ms`, con umbrales 3 y 2). Una vez inicializada la base de datos, los ajustes persistidos en SQLite tienen **prioridad absoluta en los reinicios**; modificaciones en YAML o variables de entorno no sobrescriben la configuración almacenada.
- **Validación de rangos**: `probeIntervalSeconds` (1 a 86 400 s), `probeTimeoutMs` (100 a 60 000 ms), `failureThreshold` (1 a 100 sondeos) y `recoveryThreshold` (1 a 100 sondeos).
- **Restricción temporal**: El timeout de sondeo debe ser estrictamente menor al intervalo efectivo convertido a milisegundos (`probeTimeoutMs < probeIntervalSeconds * 1000`).
- **Validación cruzada**: Si una actualización de ajustes globales invalida el timeout o intervalo de backends existentes, se rechaza con HTTP 400 y se informa la lista de IDs de rutas afectadas.
- **Herencia y overrides por backend**: Cada ruta puede definir un objeto `monitoring` con los mismos 4 campos opcionales. Cualquier campo con valor `null` o no definido hereda el valor global. En `PUT /api/admin/routes/{id}`, omitir el objeto `monitoring` conserva los overrides actuales; enviar `monitoring` con campos `null` elimina el override correspondiente y restablece la herencia. Las respuestas devuelven siempre `effectiveMonitoring` con los 4 valores resueltos.
- **Aplicación en caliente**: Los cambios se guardan y aplican de forma inmediata sin necesidad de reiniciar el servicio, reprogramando los temporizadores en memoria.

### Notificaciones por Telegram e integración con BotFather

El gateway incluye soporte nativo para despachar alertas de incidentes (apertura de corte y recuperación) hacia Telegram mediante la API oficial de bots, empleando un outbox transaccional persistido en SQLite y un despachador asíncrono desacoplado.

La configuración global de Telegram se gestiona mediante `/api/admin/settings`:
```json
{
  "telegram": {
    "enabled": true,
    "chatId": "-1001234567890"
  }
}
```
En las respuestas de consulta `GET /api/admin/settings`, el servidor añade el campo informativo:
```json
{
  "monitoring": { ... },
  "telegram": {
    "enabled": false,
    "chatId": null,
    "tokenConfigured": false
  }
}
```

1. **Gestión de secretos y token de bot (`telegram.botToken`)**:
   - El token del bot se administra como un campo **estrictamente write-only** en el cuerpo de peticiones `PUT /api/admin/settings`:
     - **Omitido**: Conserva el token de bot existente en la base de datos sin modificarlo.
     - **Cadena vacía (`""`)**: Elimina explícitamente el token del bot de la base de datos.
     - **Cadena no vacía**: Establece o reemplaza el token, almacenándolo cifrado con AES-256-GCM (debe contener exclusivamente caracteres seguros `[A-Za-z0-9:_-]`, no vacío).
   - **Operación atómica**: Se puede enviar `telegram: { enabled: true, chatId: "...", botToken: "..." }` en una sola solicitud.
   - **Regla de integridad**: Si se intenta eliminar el token (`botToken: ""`) manteniendo `enabled: true`, la solicitud es rechazada con **HTTP 400 sin realizar cambios** (las alertas no pueden estar habilitadas sin token).
   - En las respuestas `GET /api/admin/settings`, el valor booleano `tokenConfigured` informa si existe un token configurado y cifrado en SQLite. La API nunca devuelve el token ni en GET ni en PUT.
   - Este indicador certifica la presencia del secreto en la base de datos local; no valida la autenticidad del token ante Telegram ni los permisos del bot en el chat de destino.
   - Cualquier intento de activar Telegram (`enabled: true`) sin tener un token configurado o sin un Chat ID destino es rechazado con **HTTP 400**.

2. **Herencia y configuración por backend**:
   - Cada ruta admite un objeto `telegram: { enabled, chatId }` y expone en las consultas su valor resuelto en `effectiveTelegram: { enabled, chatId }`.
   - `telegram.enabled` en `settings` actúa como **interruptor maestro**: si está desactivado (`false`), ninguna ruta puede emitir alertas.
   - En una ruta:
     - `enabled: null` (o no definido) hereda el estado del interruptor global.
     - `enabled: false` **excluye explícitamente** a la ruta de emitir alertas hacia Telegram, incluso si el interruptor global está encendido.
     - `enabled: true` participa en los envíos (siempre y cuando el interruptor maestro global esté habilitado).
     - `chatId: null` hereda el Chat ID configurado a nivel global. Un valor numérico o cadena de Chat ID propio actúa como override para esa ruta.
   - En `PUT /api/admin/routes/{id}`, omitir el objeto `telegram` conserva los overrides existentes; enviar campos con valor `null` elimina el override correspondiente y restablece la herencia.

3. **Outbox transaccional y ciclo de vida de alertas**:
   - Cuando se confirma un incidente (apertura por alcanzar `failureThreshold` o cierre por `recoveryThreshold`), si la ruta participa en las notificaciones, la transición de estado y el registro del mensaje en la tabla outbox se ejecutan dentro de la **misma transacción atómica en SQLite**. Si la base de datos falla, ni el incidente ni la notificación se comprometen.
   - El worker de despacho opera de forma completamente asíncrona, en segundo plano y fuera del monitor compartido de peticiones de tráfico MQTT.
   - El despachador aplica límites internos de despacho defensivos:
     - Al menos 3 segundos entre envíos al mismo chat y un máximo global de 10 intentos por segundo (no garantizan evitar respuestas HTTP 429 impuestas por Telegram; ante un 429 el despachador respeta estrictamente el tiempo indicado en `parameters.retry_after`).
     - Timeout HTTP de 5 segundos por intento.
     - **5 intentos totales (1 envío inicial + hasta 4 reintentos)** con backoff exponencial y jitter (esperas base de 10 s, 30 s, 120 s y 300 s).
     - Errores de servidor (HTTP 5xx) y timeouts sí se reintentan; errores de cliente permanentes (HTTP 4xx, salvo 429) no se reintentan y marcan la notificación como `failed`. Si se agotan los 5 intentos totales sin éxito, la notificación pasa igualmente a estado `failed`.
   - **Expiración y retención**: Los mensajes pendientes expiran tras 24 horas si no pudieron entregarse por caída continua de red (`expired`). El historial de notificaciones en estados terminales se retiene por 30 días calculados desde su fecha de creación (`createdAt` / columna `created_at` en SQL).
   - **Capacidad de cola**: La cola admite hasta 10 000 eventos pendientes o en envío; ante una saturación extrema, los nuevos eventos se registran como `not_enqueued` sin bloquear la apertura del incidente ni degradar el enrutamiento MQTT.
   - **Semántica At Least Once y ausencia de entrega garantizada**: En caso de particiones de red donde Telegram reciba el mensaje pero el gateway pierda la confirmación HTTP, el reintento puede originar notificaciones duplicadas en el chat. No se asume entrega exactly-once. Tampoco existe garantía absoluta de entrega en todos los escenarios: mensajes pueden ser descartados por saturación de cola (límite de 10 000 eventos en `not_enqueued`), cancelados administrativamente al pausar o borrar una ruta (`cancelled`), fallar por errores permanentes de cliente (`HTTP 4xx` como bot bloqueado 403), o expirar tras 24 horas de corte de red continuado (`expired`).
   - **Pausas, borrados y deshabilitación**: Desactivar el interruptor global cancela todos los mensajes pendientes en cola. Pausar o eliminar una ruta cancela sus mensajes pendientes y no genera un aviso técnico de recuperación. Modificar el destino de una ruta no redirige mensajes ya encolados. Cancelaciones no pueden deshacer envíos ya procesados por Telegram.

4. **Prueba de notificación (`POST /api/admin/notifications/test`)**:
   - Permite encolar un mensaje de prueba hacia el destino configurado enviando `{}` (destino global) o `{ "backendId": "<id>" }`.
   - Exige que el destino esté efectivamente habilitado, con token activo y Chat ID configurado. Rutas pausadas (`enabled: false`) son rechazadas. No admite textos arbitrarios ni destinos libres desde la solicitud.
   - Responde inmediatamente con **HTTP 202 Accepted** `{ "id": "...", "status": "pending" }` o `{ "id": "...", "status": "not_enqueued" }`. El estado `not_enqueued` se devuelve si la capacidad máxima de la cola (10 000 notificaciones) ha sido alcanzada. El código 202 confirma que la solicitud ha sido procesada por el outbox para su envío en segundo plano, no que haya sido entregada de forma síncrona a Telegram.

5. **Historial de notificaciones (`GET /api/admin/notifications`)**:
   - Admite paginación con query parameters `limit` (por defecto 25, máx 100) y `offset`.
   - Respuesta estructurada:
     ```json
     {
       "items": [
         {
           "id": "notif-uuid",
           "backendId": "core-service",
           "incidentId": "inc-uuid",
           "type": "incident.opened",
           "chatId": "-1001234567890",
           "status": "sent",
           "attempts": 1,
           "createdAt": "2026-11-14T12:00:00Z",
           "nextAttemptAt": "2026-11-14T12:00:00Z",
           "sentAt": "2026-11-14T12:00:02Z",
           "lastError": null
         }
       ],
       "total": 1,
       "limit": 25,
       "offset": 0,
       "summary": {
         "pending": 0,
         "sending": 0,
         "sent": 1,
         "failed": 0,
         "expired": 0,
         "cancelled": 0,
         "notEnqueued": 0,
         "oldestPendingAt": null
       }
     }
     ```
   - Tipos de notificación: `incident.opened`, `incident.recovered` y `test`.
   - Estados de entrega: `pending`, `sending`, `sent` (la API de Telegram respondió `HTTP 200` con `{"ok":true}`), `failed`, `expired`, `cancelled` y `not_enqueued`.
   - Todos los mensajes de error en `lastError` son saneados automáticamente para garantizar que ninguna URL con tokens de bot quede registrada o visible.

6. **Guía de puesta en marcha rápida con BotFather**:
   - Para instrucciones paso a paso sobre cómo desplegar con Docker Compose, configurar la clave maestra y gestionar las alertas de Telegram desde el panel, consulta la [Guía de Telegram en Docker Compose](telegram-docker.es.md).
   - Para una visión técnica de la arquitectura de las tres fases (persistencia SQLite WAL, histéresis de monitoreo y outbox transaccional), consulta [Las tres fases del gateway](three-phases.es.md).

Un backend contiene name, enabled, authUrl, aclUrl, healthUrl,
authPrefixes (lista), aclPrefixes (lista), apiKey opcional (campo write-only cifrado con AES-256-GCM, hasApiKey expuesto en GET), telegram opcional y monitoring opcional. El servidor asigna id.
Solo se aceptan URLs HTTP(S), sin usuario/password embebidos. No se admiten
prefijos exactos duplicados entre rutas activas. healthUrl debe ser un GET
seguro, sin efectos secundarios; inicialmente se usa la raíz del backend.

La SPA intenta el token compartido de localStorage o sessionStorage y
el token de la sesión del panel. Acepta también /gateway/#token=... y limpia el
fragmento con history.replaceState. La verificación con el proveedor configurado siempre es obligatoria.
Al incrustar un iframe del mismo origen, el panel envía a su padre
`{type: "mqtt-gateway:request-token"}` (o el legado `"emqx-gateway:request-token"`);
el padre puede responder con `{token: token}` o `{emqxToken: token}`, usando el origen exacto. Se rechazan otros orígenes y
emisores. El login es la alternativa si no hay sesión válida.
X-Frame-Options SAMEORIGIN y CSP frame-ancestors self permiten esta integración.

Salir elimina la sesión del panel. En modo emqx, el usuario debe poder
iniciar sesión con contraseña y tener acceso a /api/v5/users.

## Métricas y disponibilidad

Cada llamada se cronometra con System.nanoTime(). Actual, promedio, mínima y
máxima cubren tráfico desde el arranque; P95 usa el percentil de rango más
cercano de las últimas 2 000 llamadas por backend. El sondeo tiene latencia
separada y no altera esas estadísticas. El timeout de autenticación y ACL
(`gateway.timeout-ms`, por defecto 2 000 ms) es independiente del timeout de sondeo.

Normal: menos de 200 ms; Degraded: entre 200 y 1000 ms; Slow: más de 1000 ms.
Offline, Paused y Unknown distinguen indisponibilidad, pausa y estado sin confirmar.
Un GET de sondeo considera disponible una respuesta HTTP menor que 500,
incluidos 401/403/404: confirma servicio accesible, no éxito de autenticación.
El panel muestra el HTTP del sondeo. Para verificar funcionalidad profunda,
configurar un endpoint health dedicado que devuelva 5xx al fallar.

### Política de detección y apertura de incidentes

1. **Sondeo automático periódico**:
   - Cada backend cuenta con su propio ciclo de sondeo: el siguiente sondeo comienza cuando termina el anterior y transcurre este intervalo (`probeIntervalSeconds`).
   - Una caída se confirma únicamente tras acumular `failureThreshold` fallos consecutivos (timeouts, caídas de conexión o HTTP >= 500). En ese momento, se abre un incidente persistente en SQLite y el estado pasa a `Offline`.
   - La recuperación exige acumular `recoveryThreshold` éxitos consecutivos (HTTP < 500), cerrando el incidente activo con motivo `Recovered` y regresando a `Normal`.
   - Estimación aproximada de detección:
     - Detección de caída: entre `(failureThreshold - 1) * probeIntervalSeconds` y `failureThreshold * (probeIntervalSeconds + probeTimeoutMs / 1000)` (ej. con 30 s de intervalo, 2 000 ms de timeout y umbral 3, el rango aproximado es de 60 a 96 segundos).
     - Confirmación de recuperación: entre `(recoveryThreshold - 1) * probeIntervalSeconds` y `recoveryThreshold * (probeIntervalSeconds + probeTimeoutMs / 1000)` (ej. con 30 s de intervalo, 2 000 ms de timeout y umbral 2, el rango aproximado es de 30 a 64 segundos).
   - Pausar o eliminar una ruta cierra administrativamente cualquier incidente abierto con motivo explícito, sin simular una recuperación técnica.

2. **Sondeo manual aislado (`POST /api/admin/routes/{id}/test`)**:
   - Ejecuta una comprobación puntual inmediata del backend.
   - Devuelve el estado en vivo (`online`, `latencyMs`, `httpStatus`) sin alterar los contadores consecutivos, sin abrir o cerrar incidentes y sin computar en las estadísticas de uptime.
   - Si el slot de sondeo del backend está ocupado por una ejecución en curso (automática o manual), responde con HTTP 409 (*Conflict*).

3. **Métricas y streaming SSE**:
   - `GET /api/admin/metrics` y el flujo SSE exponen:
     - `consecutiveFailures`: contador de fallos consecutivos en observación.
     - `consecutiveSuccesses`: contador de éxitos consecutivos en observación.
     - `pendingState`: estado de transición en curso (`null`, `"failure"` o `"recovery"`).
     - `awaitingObservation`: booleano que indica si el backend espera su primer sondeo tras reinicio o cambio de política.
     - `status`: estado de disponibilidad confirmado por umbrales.
     - `settings`: configuración global de monitoreo en el flujo de eventos.

4. **Comportamiento ante reinicios**:
   - Tras reiniciar el proceso, el estado de cada ruta inicia en `Unknown` con `awaitingObservation=true` y los contadores en cero.
   - Si existía un incidente abierto previo al reinicio, se conserva en SQLite y requerirá `recoveryThreshold` éxitos consecutivos para cerrarse.
   - Si no existía incidente previo, el primer sondeo exitoso confirma la operatividad sin emitir eventos de recuperación.

El uptime se calcula por intervalos de sondeo observados durante esta sesión.
No incluye pausas ni huecos prolongados sin sondeo; se reinicia al arrancar.
Los incidentes sí sobreviven a reinicios y se conservan al borrar rutas.
El buffer en memoria y diario incluye fecha, tipo, cliente, tópico, resultado,
backend y latencia (hasta 2 000 eventos); no persiste cuerpos ni credenciales.

## Nginx y servicio de usuario

Para un despliegue bajo el mismo origen (ej. `https://mqtt-panel.example.com`), el proxy debe
dirigir /gateway/ y /api/admin/ a 127.0.0.1:8085, conservando Authorization.
El resto del Dashboard puede continuar en 127.0.0.1:18083. Ejemplo de locations
dentro del server HTTPS existente:

```nginx
location /gateway/ {
    proxy_pass http://127.0.0.1:8085;
}
location /api/admin/ {
    proxy_set_header Authorization $http_authorization;
    proxy_pass http://127.0.0.1:8085;
}
```

El archivo mqtt-gateway.service de la raíz es una plantilla de unidad de
sistema. Adapta usuario, grupo, rutas y entorno antes de instalarla. Las unidades
de usuario se administran por separado con systemctl --user.

## Adaptadores MQTT

Todos los endpoints usan POST. El prefijo común es `/api/v1/gateway`.
La configuración de rutas y claves de backend se comparte entre adaptadores.
No es necesario instalar ni ejecutar EMQX para usar el gateway.

| Bróker | Autenticación | Autorización |
| --- | --- | --- |
| EMQX | /emqx/auth | /emqx/acl |
| Mosquitto | /mosquitto/auth | /mosquitto/acl |
| VerneMQ | /vernemq/auth | /vernemq/acl |
| Genérico | /generic/auth | /generic/acl |

`/auth` y `/acl` conservan el comportamiento EMQX anterior.
No se exige token administrativo en estos endpoints. La cabecera
`X-EMQX-API-KEY` se configura por backend y solo se añade en la salida;
no autentica al bróker que llama al gateway.

### EMQX y Genérico

Enviar `Content-Type: application/json`. Auth recibe
`{"clientid":"device_sensor_01","username":"usuario","password":"clave"}`.
ACL recibe `{"clientid":"device_sensor_01","username":"usuario","topic":"devices/telemetry","action":"publish"}`;
para suscripciones, `action` es `subscribe`. Configurar el cuerpo HTTP de
EMQX con estas propiedades y apuntar a las rutas /emqx/auth y /emqx/acl.
EMQX recibe HTTP 200 con `{"result":"allow"}`, `{"result":"deny"}` o
`{"result":"ignore"}`. En allow se conserva `is_superuser`.
Genérico recibe HTTP 200 con `{"allowed":true,"status":"allow"}`,
`{"allowed":false,"status":"deny"}` o `{"allowed":false,"status":"ignore"}`.

### Mosquitto con mosquitto-go-auth

Configurar el backend HTTP del complemento: `auth_opt_backends http`,
`auth_opt_http_host 127.0.0.1`, `auth_opt_http_port 8085`,
`auth_opt_http_getuser_uri /api/v1/gateway/mosquitto/auth`,
`auth_opt_http_aclcheck_uri /api/v1/gateway/mosquitto/acl`,
`auth_opt_http_method POST`, `auth_opt_http_params_mode json` y
`auth_opt_http_response_mode status`. La ruta del complemento nativo
`auth_plugin` depende de la instalación de Mosquitto.

Auth recibe `{"clientid":"device_sensor_01","username":"usuario","password":"clave"}`.
ACL recibe `{"clientid":"device_sensor_01","username":"usuario","topic":"devices/telemetry","acc":2}`.
`acc=2` se traduce a `publish`; `acc=1` y `acc=4` a `subscribe`.
Otros valores se deniegan. Se admiten JSON y formularios
`application/x-www-form-urlencoded` con esos mismos campos.
Respuesta: HTTP 200 para allow, 400 para deny y 404 para ignore,
con `result` en JSON para diagnóstico. En modo status el complemento
interpreta tanto 400 como 404 como un resultado no permitido; la cadena
de backends del bróker determina si se prueban otros proveedores.

### VerneMQ con vmq_webhooks

Activar `plugins.vmq_webhooks = on`. Registrar tres webhooks:
`auth_on_register` apunta a `/api/v1/gateway/vernemq/auth`;
`auth_on_publish` y `auth_on_subscribe` apuntan a
`/api/v1/gateway/vernemq/acl`. Para cada webhook configurar
`vmq_webhooks.<nombre>.hook` y `vmq_webhooks.<nombre>.endpoint`
con el hook y la URL completa. Se admiten también los nombres de esos
tres hooks terminados en `_m5` para MQTT 5.

El complemento envía JSON y la cabecera `vernemq-hook`.
Auth recibe `{"client_id":"device_sensor_01","username":"usuario","password":"clave"}`.
Publicación recibe `{"client_id":"device_sensor_01","username":"usuario","topic":"devices/telemetry","qos":1}`.
Suscripción recibe `{"client_id":"device_sensor_01","username":"usuario","topics":[{"topic":"devices/telemetry","qos":1}]}`.
`client_id` se copia a `clientid`; el hook de publicación añade
`action=publish` y cada entrada de `topics` añade `action=subscribe`.
Cada tópico tiene resolución, métrica y auditoría propias.
Todos deben devolver allow: un deny deniega el conjunto; sin deny,
un ignore produce next. Una lista vacía o inválida se deniega.
Las respuestas usan HTTP 200: `{"result":"ok"}` para allow,
`{"result":{"error":"not_allowed"}}` para deny y
`{"result":"next"}` para ignore. No se devuelven modificadores
de tópicos ni privilegios de superusuario específicos de VerneMQ.

### Contrato saliente compartido

Los adaptadores traducen entrada y respuesta; los backends HTTP continúan
recibiendo el contrato JSON existente (`clientid`, `username`, `password`
para auth; `clientid`, `username`, `topic`, `action` para ACL).
Se conservan campos adicionales, incluyendo los originales del bróker.
Los backends responden con HTTP 2xx y `result=allow|deny|ignore`.
La selección sigue siendo por prefijo de clientid para auth y de topic
para ACL. JSON inválido, timeout y fallo del backend producen deny.
Los privilegios de superusuario siguen siendo exclusivos del formato EMQX;
en los otros brókers la autorización ACL se evalúa por separado.

Referencias del protocolo: [mosquitto-go-auth](https://github.com/iegomez/mosquitto-go-auth#http)
y [webhooks de VerneMQ](https://docs.vernemq.com/plugin-development/webhookplugins).

## Autenticación autónoma del panel

La configuración está bajo `gateway.admin` en application.yml:

| Propiedad | Variable de entorno | Valor por defecto |
| --- | --- | --- |
| auth-provider | GATEWAY_ADMIN_AUTH_PROVIDER | internal |
| username | GATEWAY_ADMIN_USERNAME | admin |
| password | GATEWAY_ADMIN_PASSWORD | admin-secret-2026$ |

En modo `internal`, POST `/api/admin/auth/login` recibe
`{"username":"admin","password":"<contraseña configurada>"}` y devuelve
`{"token":"<UUID aleatorio>","username":"admin"}`. Credenciales incorrectas:
HTTP 401; campos vacíos: HTTP 400. El token se conserva únicamente en memoria
y caduca ocho horas después de su emisión, sin renovación por actividad.
Se pueden emitir varias sesiones. Reiniciar invalida todas las sesiones.
Las entradas caducadas se eliminan al verificarlas y en cada login exitoso.

Todas las operaciones administrativas protegidas y GET/POST
`/api/admin/auth/verify` requieren `Authorization: Bearer <token>`.
La verificación correcta devuelve `{"valid":true}`; un token ausente,
desconocido o caducado devuelve HTTP 401. El panel sigue funcionando
si EMQX está detenido. Configurar GATEWAY_ADMIN_PASSWORD en el entorno
del servicio para sustituir la contraseña inicial.

En modo `emqx`, login delega en POST `/api/v5/login` y cada verificación
delega en GET `/api/v5/users` del Dashboard indicado por GATEWAY_EMQX_URL.
Los tokens internos no sirven en modo EMQX, ni los de EMQX en modo interno.
No hay cambio automático de proveedor ante un fallo de EMQX.
La integración existente por fragmento, almacenamiento e iframe continúa
siempre que el token corresponda al proveedor configurado.
Salir del panel elimina su sesión del navegador; el token interno sigue
vigente hasta caducar o reiniciar. No se añade un endpoint de revocación.

## Copia para desarrollo y publicación

Se requiere JDK 25. En Windows se puede compilar con `mvnw.cmd package`;
las pruebas smoke y el despliegue systemd se ejecutan en Linux o WSL.

`mqtt-gateway.service` es una plantilla de servicio de sistema: adaptar el usuario,
el grupo y la ruta de instalación (por defecto `/opt/mqtt-gateway`).
Las credenciales de configuración son valores iniciales de ejemplo; definir
GATEWAY_ADMIN_PASSWORD y EMQX_SUPERUSER_PASSWORD propios antes de desplegar.
Los datos de ejecución, los archivos .env, las claves privadas y los artefactos
de compilación están excluidos de Git. El script verify_sse_metrics.py requiere
GATEWAY_ADMIN_PASSWORD en el entorno y no muestra el token recibido.

## Validación y pruebas del proyecto

El proyecto cuenta con suites de verificación sobre JDK 25 que cubren las tres fases:
- **Pruebas Unitarias y de Integración (JUnit sobre JVM)**: 35 tests automatizados (12 de monitoreo con histéresis, 14 de DAO y worker outbox con mocks locales HTTP, y 9 de secretos administrados/canary criptográfico) que validan persistencia en SQLite WAL, snapshots periódicos a `checkpoints`, histéresis de incidentes, herencia de overrides, despacho outbox con 5 intentos totales y cifrado AES-256-GCM en reposo.
- **Suites HTTP en Python**: Verificadas tanto en modo JVM como en binario nativo compilado con GraalVM sobre Linux.
- **Pruebas de UI e Integración de Brókers**: Pruebas de navegador con Playwright y suite de entrega de mensajes MQTT 5 sobre Mosquitto con Docker Compose.
- **Prueba Real con Telegram**: **La prueba real de extremo a extremo con BotFather y chat de Telegram SIGUE PENDIENTE** y no forma parte del pipeline automatizado para no requerir credenciales externas en CI. Requiere que el operador configure un token legítimo en su entorno y ejecute la prueba manualmente desde el panel web.
