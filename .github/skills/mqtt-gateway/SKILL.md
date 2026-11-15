---
name: mqtt-gateway
description: Guía de integración y operaciones para agentes de IA con MQTT Gateway. Úsalo para entender contratos de autenticación, ACLs, formatos de sondeo, administración de rutas y diagnóstico de incidentes.
---

# MQTT Gateway Agent Skill

Esta habilidad proporciona a los agentes de IA las instrucciones y contratos precisos para operar, configurar e integrar sistemas con MQTT Gateway de forma autónoma y sin alucinaciones.

> [!IMPORTANT]
> **Única fuente de verdad del contrato:** El código fuente del repositorio (`AdminResource.java`, `GatewayState.java`, `StorageService.java`, `GatewayConfig.java`, `application.yml`) es la máxima autoridad técnica y contractual del sistema. Planes y auditorías son antecedentes históricos.

## 1. Arquitectura de Endpoints

MQTT Gateway recibe tráfico de brókers MQTT (EMQX, Mosquitto, VerneMQ) y lo traduce a llamadas HTTP hacia backends registrados.

### A. Endpoints de Tráfico (Públicos del Bróker)
- **POST `/api/v1/gateway/emqx/auth`**:
  - Request: `{"clientid":"<id>","username":"<user>","password":"<pass>"}`
  - Response: HTTP 200 `{"result":"allow"|"deny"|"ignore","is_superuser":boolean}`
- **POST `/api/v1/gateway/emqx/acl`**:
  - Request: `{"clientid":"<id>","username":"<user>","topic":"<topic>","action":"publish"|"subscribe"}`
  - Response: HTTP 200 `{"result":"allow"|"deny"|"ignore"}`
- **POST `/api/v1/gateway/mosquitto/auth`**: Adaptador mosquitto-go-auth (HTTP 200 allow, 400 deny, 404 ignore).
- **POST `/api/v1/gateway/mosquitto/acl`**: Adaptador mosquitto-go-auth (`acc`: 1=sub, 2=pub).
- **POST `/api/v1/gateway/vernemq/auth`**: Webhook `auth_on_register`.
- **POST `/api/v1/gateway/vernemq/acl`**: Webhooks `auth_on_publish` y `auth_on_subscribe`.
- **POST `/api/v1/gateway/generic/auth`** y **/acl**: Formato agnóstico `{"allowed":true|false,"status":"allow"|"deny"}`.

### B. Contrato Saliente hacia tus Microservicios / Backends
Cuando el gateway enruta una petición a tu backend:
- Envía una petición `POST` a tu `authUrl` o `aclUrl`.
- Si el backend tiene clave configurada, el gateway inyecta la cabecera `X-EMQX-API-KEY: <clave>` (almacenada cifrada con AES-256-GCM en SQLite y descifrada en memoria privada en caliente).
- Tu backend debe responder HTTP 2xx con `{"result":"allow"}` o `{"result":"deny"}`.

### C. Sondeo de Salud (Health Probe)
- El gateway ejecuta periódicamente un `GET` a tu `healthUrl`.
- Debe ser una llamada ligera, sin efectos secundarios, que responda en menos de 2 000 ms.
- Respuestas HTTP < 500 (incluidos 200, 401, 403, 404) confirman que el servicio está accesible. Códigos HTTP >= 500 o timeouts acumulan fallos y abren un incidente formal únicamente tras alcanzar consecutivamente el umbral configurable (`failureThreshold`, por defecto 3 fallos).

## 2. API Administrativa (Requiere `Authorization: Bearer <token>`)

- **Login**: `POST /api/admin/auth/login` con `{"username":"admin","password":"<pass>"}` -> retorna `{"token":"<uuid>"}`.
- **Ajustes globales**: `GET /api/admin/settings` y `PUT /api/admin/settings` con `{ "monitoring": { ... }, "telegram": { "enabled": true, "chatId": "-1001234567890", "botToken": "..." } }`. Permite configurar en caliente monitoreo y alertas. Omitir una sección conserva sus valores previos. `telegram.botToken` es *write-only* (omitido conserva, `""` elimina, cadena no vacía establece); la API devuelve `tokenConfigured: boolean` y exige un token configurado para habilitar alertas. Eliminar el token (`botToken: ""`) exige desactivar Telegram (`enabled: false`) en la misma solicitud o retornará HTTP 400. Sembrado inicial vía `gateway.monitoring.*` / `GATEWAY_MONITORING_*`; en reinicios SQLite tiene prioridad absoluta.
- **Listar rutas**: `GET /api/admin/routes` -> array de backends, incluyendo `hasApiKey: boolean`, `effectiveMonitoring` y `effectiveTelegram` computados.
- **Registrar backend**: `POST /api/admin/routes`:
  ```json
  {
    "name": "Servicio Telemetría",
    "enabled": true,
    "authPrefixes": ["tel_"],
    "aclPrefixes": ["telemetry/"],
    "authUrl": "http://127.0.0.1:8080/auth",
    "aclUrl": "http://127.0.0.1:8080/acl",
    "healthUrl": "http://127.0.0.1:8080/health",
    "apiKey": "clave-secreta-backend",
    "monitoring": {
      "probeIntervalSeconds": 15,
      "probeTimeoutMs": 1500,
      "failureThreshold": 2,
      "recoveryThreshold": 1
    },
    "telegram": {
      "enabled": true,
      "chatId": "-1009876543210"
    }
  }
  ```
  *(Nota: `apiKey` es write-only: en PUT omitido conserva, `""` elimina, cadena no vacía establece/reemplaza (máximo 4096 caracteres ASCII imprimibles sin caracteres de control, rango 0x20 a 0x7E); GET responde `hasApiKey: boolean`. `apiKeyEnv` está en desuso y se rechaza con HTTP 400 si se envía con valor no vacío. En ajustes, `telegram.botToken` es write-only aceptando exclusivamente caracteres seguros `[A-Za-z0-9:_-]`, GET responde `tokenConfigured: boolean`. Se requiere la variable `GATEWAY_MASTER_KEY` de 32 bytes Base64 en el entorno de arranque para el cifrado AES-256-GCM. En `monitoring` y `telegram`, cualquier campo omitido o con valor `null` hereda del global; `telegram.enabled: false` excluye explícitamente el backend de alertas. Omitir estos objetos en PUT conserva los overrides previos).*
- **Sondeo manual**: `POST /api/admin/routes/{id}/test` -> ejecuta sondeo puntual aislado sin alterar métricas históricas ni abrir/cerrar incidentes (devuelve HTTP 409 si el slot está ocupado).
- **Notificaciones e historial outbox**: `GET /api/admin/notifications?limit=25&offset=0` -> historial paginado (`items`, `total`, `limit`, `offset`, `summary` con `pending`, `sending`, `sent`, `failed`, `expired`, `cancelled`, `notEnqueued`, `oldestPendingAt`).
- **Prueba de notificación**: `POST /api/admin/notifications/test` con `{ "backendId": "<id-opcional>" }` -> devuelve HTTP 202 Accepted `{ "id": "<uuid>", "status": "pending" | "not_enqueued" }`. Indica procesamiento en outbox para envío en segundo plano (estado `not_enqueued` ante cola saturada de 10 000 eventos).
- **Métricas**: `GET /api/admin/metrics` -> estadísticas P95, mínima, máxima, promedio, contadores consecutivos (`consecutiveFailures`, `consecutiveSuccesses`), estado de transición (`pendingState`) y bandera de observación (`awaitingObservation`).
- **Auditoría**: `GET /api/admin/logs` -> registro en memoria de las últimas 2 000 decisiones tomadas.
- **Incidentes**: `GET /api/admin/incidents` -> historial persistido de cortes e indisponibilidad.

## 3. Acceso a Documentación Nativa para Agentes
- **Endpoint llms.txt**: `GET /gateway/llms.txt` o `GET /llms.txt`.
- **Referencia técnica en Markdown crudo**:
  - `GET /gateway/docs` con cabecera `Accept: text/markdown`
  - o directamente `GET /gateway/docs.md` o `GET /gateway/llms-full.txt`.
- **Guías Arquitectónicas y Operativas**:
  - [Telegram en Docker Compose](../../../docs/telegram-docker.es.md): Configuración de alertas por Telegram y secretos administrados con Docker Compose.
  - [Las tres fases del gateway](../../../docs/three-phases.es.md): Persistencia SQLite WAL con secretos cifrados AES-256-GCM, histéresis de monitoreo y outbox transaccional.
