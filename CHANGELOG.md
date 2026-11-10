# Cambios

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
