# Demo MQTT con Docker Compose

[English](compose.md) | [Español](compose.es.md) | [Presentación](../README.es.md)

Esta demo local ejecuta MQTT Gateway, un backend de políticas en Python y
Mosquitto con mosquitto-go-auth. Las decisiones de autenticación y ACL pasan por
`/mosquitto/auth` y `/mosquitto/acl`. Los mensajes MQTT permanecen en Mosquitto.

Requisitos: Docker Engine o Docker Desktop con **contenedores Linux** y
Docker Compose **2.20 o posterior**. No necesitas JDK, Python ni clientes MQTT
instalados en el host. La imagen de bróker validada es Linux AMD64; la ejecución
o emulación ARM no está validada.

## Iniciar

Desde una copia de esta versión del repositorio:

```sh
docker compose up --build --wait
```

La primera compilación descarga imágenes, Maven y dependencias Java. Las
siguientes reutilizan capas en caché. El proceso de inicialización registra
**Local demo** de forma idempotente y pausa las tres rutas iniciales ajenas
a este entorno.

| Servicio | Dirección local / credenciales |
| --- | --- |
| Panel | <http://127.0.0.1:18085/gateway/> |
| Documentación | <http://127.0.0.1:18085/gateway/docs> |
| Administrador | `admin` / `local-demo-admin-2026` |
| Listener MQTT | `127.0.0.1:18883` |
| Credenciales MQTT | `demo-user` / `demo-password`; ClientID debe comenzar por `demo_` |

Son credenciales públicas de ejemplo. Los puertos publicados se vinculan solo a
loopback del host. El backend y el inicializador no publican puertos en el host.

Para usar otros puertos, crea un archivo `.env` excluido de Git:

```dotenv
DEMO_HTTP_PORT=18088
DEMO_MQTT_PORT=18889
```

No ejecutes la suite smoke en su puerto predeterminado 18085 mientras la demo
lo utiliza. Detén la demo o cambia `DEMO_HTTP_PORT`.

## Ejecutar pruebas MQTT reales

```sh
docker compose --profile test run --rm mqtt-tests
```

El servicio utiliza los clientes MQTT 5 de Mosquitto y termina con estado
distinto de cero si una comprobación falla. Verifica:

- Un suscriptor autorizado recibe exactamente el mensaje del publicador autorizado.
- Una contraseña incorrecta provoca el rechazo de la conexión MQTT.
- Se rechaza publicar en `demo/private/secret`.
- Se rechaza suscribir a `demo/private/secret`.

El cliente incluido puede terminar con estado cero ante ciertas operaciones
rechazadas. Las pruebas inspeccionan los códigos MQTT CONNACK/PUBACK/SUBACK;
no confunden ese estado de salida con una autorización de publicación o suscripción.

Política de ejemplo: ClientID con prefijo `demo_`, credenciales `demo-user` y
permisos de publicación/suscripción en `demo/*`, excepto el subárbol
`demo/private/`. Estas reglas fijas pertenecen al backend de ejemplo, no al gateway.

## Enviar un mensaje manualmente

En la terminal 1:

```sh
docker compose exec mosquitto mosquitto_sub -h 127.0.0.1 -V mqttv5 \
  -i demo_manual_sub -u demo-user -P demo-password -t demo/temperature -q 1
```

En la terminal 2:

```sh
docker compose exec mosquitto mosquitto_pub -h 127.0.0.1 -V mqttv5 \
  -i demo_manual_pub -u demo-user -P demo-password -t demo/temperature -q 1 -m 23.5
```

El suscriptor debe mostrar `23.5`. Consulta la pestaña de auditoría del gateway
para revisar las decisiones de autenticación y ACL correspondientes.

## Detener, conservar estado o restablecer

```sh
docker compose down
```

Las rutas y el estado del gateway se conservan en el volumen `gateway-data`.
Para empezar de cero, este comando también elimina el volumen de la demo:

```sh
docker compose down --volumes
```

## Alcance de v0.1.0

- La imagen del bróker/complemento está fijada por digest y contiene Mosquitto
  **2.0.15**. El proyecto original [mosquitto-go-auth](https://github.com/iegomez/mosquitto-go-auth)
  está archivado. Esta combinación sirve para evaluación local, no como receta
  de producción.
- El listener MQTT no utiliza TLS. No uses las credenciales públicas ni las reglas
  de ejemplo para un despliegue público.
- Las pruebas cubren esta imagen y MQTT 5. EMQX y VerneMQ tienen pruebas de contrato
  HTTP; esta versión no certifica interoperabilidad con esos brókers reales.
- La demo utiliza una red y un volumen aislados de Compose. No configura un bróker
  externo ni un servicio systemd existente.

El código propio está disponible bajo [GNU AGPL-3.0](../LICENSE).
Las imágenes externas y sus componentes mantienen sus licencias originales.
