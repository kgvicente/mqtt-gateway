# Referencia técnica de MQTT Gateway

[Presentación](../README.es.md) | [Inicio rápido](quickstart.es.md) | [English overview](../README.md)

Consulta contratos, configuración, métricas y operación. Para probar el proyecto
por primera vez, empieza por la guía de inicio rápido.

## Ejecutar y comprobar

Para la demo con un bróker real y contenedores, consulta [Docker Compose](compose.es.md).

```sh
./mvnw package
python3 scripts/smoke.py
./mvnw package -Dnative -Dquarkus.native.container-build=true
python3 scripts/smoke.py ./target/mqtt-gateway-0.1.0-runner
```

JDK 25 es necesario para compilar. Docker solo es necesario para la modalidad
de compilación nativa en contenedor. Maven Wrapper descarga Maven.
El ejecutable Linux queda en target/mqtt-gateway-0.1.0-runner.
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
UI_SMOKE=1 python3 scripts/smoke.py ./target/mqtt-gateway-0.1.0-runner
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
| EMQX_BACKEND_API_KEY | Vacío | API key inicial de backend |
| GATEWAY_DATA_DIR | data | Directorio de estado |
| GATEWAY_EMQX_URL | http://127.0.0.1:18083 | Dashboard EMQX (si se usa modo emqx) |
| GATEWAY_PROBE_SECONDS | 30 | Intervalo de sondeo |
| GATEWAY_TIMEOUT_MS | 2000 | Timeout de backends y EMQX |

En el primer arranque se crean data/routes.json y data/incidents.json. Las rutas
iniciales son IoT Core Service, Device Gateway y Telemetry Service. Después, routes.json es la fuente
de verdad; cambiar YAML o EMQX_BACKEND_API_KEY no sobrescribe las rutas guardadas.
Cambiar la API key desde el panel o PUT de la ruta. Los ficheros tienen modo 600,
el directorio modo 700 y están excluidos de Git. Las escrituras usan renombrado
atómico. Un JSON corrupto impide arrancar, evitando perder silenciosamente rutas.

Las API keys nunca salen en respuestas administrativas. Un PUT que omite apiKey
conserva la clave; apiKey vacío la elimina. Se envía X-EMQX-API-KEY únicamente
si está configurada, tanto en tráfico como en sondeos. No se reenvían cabeceras
entrantes del cliente. No se registran passwords, tokens o claves.

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
| GET /api/admin/routes | Listar rutas, sin claves |
| POST /api/admin/routes | Crear backend |
| PUT /api/admin/routes/{id} | Reemplazar backend, omitir apiKey para conservarla |
| DELETE /api/admin/routes/{id} | Eliminar backend, conservar incidentes |
| POST /api/admin/routes/{id}/test | Ejecutar un sondeo inmediato |
| GET /api/admin/metrics | Métricas por backend |
| GET /api/admin/incidents | Historial persistente |
| GET /api/admin/logs | Hasta 2000 eventos, más reciente primero |

PUT /api/admin/routes también acepta id en el cuerpo;
DELETE /api/admin/routes?id=... es equivalente a la variante con path.

Un backend contiene name, enabled, authUrl, aclUrl, healthUrl,
authPrefixes (lista), aclPrefixes (lista) y apiKey opcional. El servidor asigna id.
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
separada y no altera esas estadísticas.

Normal: menos de 200 ms; Degraded: entre 200 y 1000 ms; Slow: más de 1000 ms.
Offline, Paused y Unknown distinguen indisponibilidad, pausa y falta de datos.
Un GET de sondeo considera disponible una respuesta HTTP menor que 500,
incluidos 401/403/404: confirma servicio accesible, no éxito de autenticación.
El panel muestra el HTTP del sondeo. Para verificar funcionalidad profunda,
configurar un endpoint health dedicado que devuelva 5xx al fallar.

Timeouts, desconexiones y 5xx abren un incidente por backend. Fallos consecutivos
no lo duplican; una respuesta disponible lo cierra. startedAt/endedAt son fechas
UTC de detección, durationMs es la duración observada. Pausar/eliminar cierra
el incidente con endReason explícito y no simula una recuperación.

El uptime se calcula por intervalos de sondeo observados durante esta sesión.
No incluye pausas ni huecos prolongados sin sondeo; se reinicia al arrancar.
Los incidentes sí sobreviven a reinicios y se conservan al borrar rutas.
El sondeo periódico tiene una resolución de 30 segundos, no precisión continua.
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
