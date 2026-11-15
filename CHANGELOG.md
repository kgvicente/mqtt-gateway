# Cambios

## [Unreleased]

- **Gestión de secretos administrados desde el panel (V4):** Claves API de backends y token de Telegram configurables directamente desde la interfaz web o API REST, almacenados cifrados con AES-256-GCM en SQLite mediante la clave maestra obligatoria `GATEWAY_MASTER_KEY` (32 bytes Base64).
- **Contratos write-only de credenciales:** `routes.apiKey` y `settings.telegram.botToken` aceptan reemplazo o eliminación explícita sin retornar nunca secretos en respuestas, DOM ni logs; flags booleanas `hasApiKey` y `tokenConfigured` en consultas GET.
- **Eliminación de variables de entorno individuales:** Sustitución de `apiKeyEnv` y `GATEWAY_TELEGRAM_BOT_TOKEN` por el modelo administrado unificado con migración transaccional única de datos legados.
- **Notificaciones de incidentes por Telegram (Fase 3):** Despacho asíncrono mediante Transactional Outbox en SQLite con semántica at-least-once, respeto de `parameters.retry_after` (HTTP 429), reintentos con jitter y purga automática tras 30 días.
- **Monitoreo de salud e histéresis (Fase 2):** Sondeos activos periódicos con anti-flapping configurable en caliente (intervalo, timeout, umbrales de corte y recuperación) y estimación de tiempos de detección.
- **Corrección documental:** Alineación de avisos de licencia en `MANUAL_OPERACIONES.md` con la licencia GNU Affero General Public License v3.0 (AGPL-3.0) declarada en `LICENSE` y `NOTICE`.

## 0.1.0

Primera versión pública para evaluación y demostraciones locales.

- Adaptadores HTTP para EMQX, Mosquitto, VerneMQ y clientes genéricos.
- Autenticación administrativa interna o delegada a EMQX.
- Panel con rutas, sondeos, métricas, auditoría paginada y confirmación de borrado.
- README y guías de inicio en inglés y español, con capturas reproducibles.
- Demo Docker Compose con gateway, backend y Mosquitto; inicialización idempotente.
- Pruebas MQTT 5 de entrega de mensajes y rechazo de credenciales y permisos.
- Distribución JVM con JRE/JDK 25; Dockerfile con ejecución sin root.
- Código propio bajo GNU Affero General Public License v3.0 (AGPL-3.0) y opción de licenciamiento comercial; avisos de terceros conservados.

La imagen validada de la demo usa Mosquitto 2.0.15 y el complemento archivado
mosquitto-go-auth. Es una demo local sin TLS. EMQX y VerneMQ conservan validación
de contrato HTTP; no se certifica su interoperabilidad MQTT real en esta versión.
