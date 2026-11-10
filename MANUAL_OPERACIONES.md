# Manual Operativo y Guía de Integración: MQTT Auth & ACL Gateway

> [!NOTE]
> **Aviso Legal / Trademark Disclaimer:** Todas las marcas comerciales, marcas de servicio, nombres de productos y logotipos mencionados en este documento (incluidos EMQX, Mosquitto, VerneMQ y HiveMQ) son propiedad de sus respectivos dueños. Este proyecto es una herramienta comunitaria independiente y de código visible bajo licencia no comercial.

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

### 2.1. Archivo de Configuración de Rutas

El archivo principal reside en:
`src/main/resources/application.yml`

```yaml
gateway:
  superuser:
    username: ${EMQX_SUPERUSER_USERNAME:backend-system-client}
    password: ${EMQX_SUPERUSER_PASSWORD:backend-system-password-2026$}
  timeout-ms: 2000
  global-topics: [heart, ping]

  # 1. Rutas de Autenticación (enrutan por prefijo de clientid)
  auth:
    - prefixes: [core_, android_, ios_, web_]
      url: http://127.0.0.1:8090/api/v1/emqx/auth
      api-key: ${EMQX_BACKEND_API_KEY:}

    - prefixes: [dev_]
      url: http://127.0.0.1:8091/api/v1/emqx/auth

    - prefixes: [tel_]
      url: http://127.0.0.1:8080/api/v1/emqx/auth

  # 2. Rutas de Autorización ACL (enrutan por prefijo de topic)
  acl:
    - prefixes: [v1/, core/]
      url: http://127.0.0.1:8090/api/v1/emqx/acl
      api-key: ${EMQX_BACKEND_API_KEY:}

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
         api-key: ${ALERTS_API_KEY:clave-opcional}

     acl:
       # ... sistemas existentes ...
       - prefixes: [alerts/]
         url: http://127.0.0.1:8082/api/v1/emqx/acl
         api-key: ${ALERTS_API_KEY:clave-opcional}
   ```
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

Si necesitas cambiar la API Key del backend o las credenciales del superusuario del sistema, puedes configurarlas en el archivo de servicio:

`~/.config/systemd/user/mqtt-gateway.service`:
```ini
[Service]
Environment=EMQX_BACKEND_API_KEY=REEMPLAZAR_POR_CLAVE_SEGURA
Environment=EMQX_SUPERUSER_USERNAME=backend-system-client
Environment=EMQX_SUPERUSER_PASSWORD=backend-system-password-2026$
Environment=GATEWAY_ADMIN_USERNAME=admin
Environment=GATEWAY_ADMIN_PASSWORD=admin-secret-2026$
```

Tras modificar el archivo, aplica los cambios con:
```bash
systemctl --user daemon-reload
systemctl --user restart mqtt-gateway
```

---

## Módulo 4: Solución de Problemas Frecuentes (Troubleshooting)

1. **El bróker reporta `authentication_failure` o `not_authorized`:**
   * **Causa 1:** El `clientid` del dispositivo no empieza con ninguno de los prefijos configurados (`core_`, `android_`, `ios_`, `dev_`, `tel_`). En este caso, el Gateway devuelve `{"result": "ignore"}` y el bróker rechaza la conexión.
   * **Causa 2:** El backend de destino está caído o superó el timeout de 2000 ms. Revisa los logs con `journalctl --user -u mqtt-gateway -f`.
2. **El backend recibe la petición pero devuelve 403:**
   * Verifica que la variable `EMQX_BACKEND_API_KEY` en `mqtt-gateway.service` coincida exactamente con la cabecera esperada por el backend.
3. **El puerto 8085 no responde:**
   * Ejecuta `ss -tulpn | grep 8085` para confirmar si el binario está escuchando.
   * Si está detenido, revisa las causas de caída con `journalctl --user -u mqtt-gateway -n 30`.
