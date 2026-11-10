---
name: mqtt-gateway
description: Guía de integración y operaciones para agentes de IA con MQTT Gateway. Úsalo para entender contratos de autenticación, ACLs, formatos de sondeo, administración de rutas y diagnóstico de incidentes.
---

# MQTT Gateway Agent Skill

Esta habilidad proporciona a los agentes de IA las instrucciones y contratos precisos para operar, configurar e integrar sistemas con MQTT Gateway de forma autónoma y sin alucinaciones.

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
- Si configuraste `apiKey`, el gateway añade la cabecera `X-EMQX-API-KEY: <clave>`.
- Tu backend debe responder HTTP 2xx con `{"result":"allow"}` o `{"result":"deny"}`.

### C. Sondeo de Salud (Health Probe)
- El gateway ejecuta periódicamente un `GET` a tu `healthUrl`.
- Debe ser una llamada ligera, sin efectos secundarios, que responda en menos de 2 000 ms.
- Respuestas HTTP < 500 (incluidos 200, 401, 403, 404) confirman que el servicio está vivo. Códigos 5xx o timeout abren un incidente.

## 2. API Administrativa (Requiere `Authorization: Bearer <token>`)

- **Login**: `POST /api/admin/auth/login` con `{"username":"admin","password":"<pass>"}` -> retorna `{"token":"<uuid>"}`.
- **Listar rutas**: `GET /api/admin/routes` -> array de backends.
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
    "apiKey": "secreto-opcional"
  }
  ```
- **Sondeo manual**: `POST /api/admin/routes/{id}/test` -> ejecuta sondeo inmediato y devuelve latencia y estado HTTP.
- **Métricas**: `GET /api/admin/metrics` -> estadísticas P95, mínima, máxima, promedio y muestras recientes.
- **Auditoría**: `GET /api/admin/logs` -> registro en memoria de las últimas 2 000 decisiones tomadas.
- **Incidentes**: `GET /api/admin/incidents` -> historial persistido de cortes e indisponibilidad.

## 3. Acceso a Documentación Nativa para Agentes
- **Endpoint llms.txt**: `GET /gateway/llms.txt` o `GET /llms.txt`.
- **Referencia técnica en Markdown crudo**:
  - `GET /gateway/docs` con cabecera `Accept: text/markdown`
  - o directamente `GET /gateway/docs.md` o `GET /gateway/llms-full.txt`.
