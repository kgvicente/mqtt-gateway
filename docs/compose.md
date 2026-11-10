# MQTT demo with Docker Compose

[English](compose.md) | [Español](compose.es.md) | [Project overview](../README.md)

This local demo runs MQTT Gateway, a Python policy backend, and Mosquitto with
mosquitto-go-auth. Authentication and ACL decisions go through the gateway's
`/mosquitto/auth` and `/mosquitto/acl` adapters. MQTT messages stay in Mosquitto.

Requirements: Docker Engine or Docker Desktop with **Linux containers** and
Docker Compose **2.20 or later**. No host JDK, Python or MQTT clients are needed.
The validated broker image is Linux AMD64. ARM execution/emulation is not validated.

## Start

From a checkout of this release:

```sh
docker compose up --build --wait
```

The first build downloads images, Maven and Java dependencies. Subsequent builds
reuse cached layers. The bootstrap job registers **Local demo** idempotently and
pauses the three preset routes that are not part of this environment.

| Service | Local endpoint / credentials |
| --- | --- |
| Admin panel | <http://127.0.0.1:18085/gateway/> |
| Documentation | <http://127.0.0.1:18085/gateway/docs> |
| Administrator | `admin` / `local-demo-admin-2026` |
| MQTT listener | `127.0.0.1:18883` |
| MQTT credentials | `demo-user` / `demo-password`; client ID must start with `demo_` |

These are public example credentials. The demo publishes ports on host loopback
only. The backend and bootstrap have no published host ports.

For different host ports, create an untracked `.env` file:

```dotenv
DEMO_HTTP_PORT=18088
DEMO_MQTT_PORT=18889
```

Do not run the normal smoke suite on its default port 18085 while the demo uses
that same port. Either stop the demo or change `DEMO_HTTP_PORT`.

## Run the real MQTT checks

```sh
docker compose --profile test run --rm mqtt-tests
```

The test service uses Mosquitto's MQTT 5 command-line clients and exits with a
nonzero status if a check fails. It verifies:

- A permitted subscriber receives the exact message sent by a permitted publisher.
- An incorrect password rejects the MQTT connection.
- Publishing to `demo/private/secret` is rejected.
- Subscribing to `demo/private/secret` is rejected.

The bundled client can exit with status zero for some rejected operations.
The tests inspect MQTT CONNACK/PUBACK/SUBACK reason codes, rather than treating
that exit status as proof that publication or subscription was authorized.

Demo policy: valid clients use `demo_` client IDs and `demo-user` credentials;
`demo/*` permits publish/subscribe except for the `demo/private/` subtree.
These fixed policies belong to the example backend, not the gateway itself.

## Send a message manually

In terminal 1:

```sh
docker compose exec mosquitto mosquitto_sub -h 127.0.0.1 -V mqttv5 \
  -i demo_manual_sub -u demo-user -P demo-password -t demo/temperature -q 1
```

In terminal 2:

```sh
docker compose exec mosquitto mosquitto_pub -h 127.0.0.1 -V mqttv5 \
  -i demo_manual_pub -u demo-user -P demo-password -t demo/temperature -q 1 -m 23.5
```

The subscriber should print `23.5`. Check the gateway panel's audit tab to inspect
the corresponding authentication and ACL decisions.

## Stop, keep state or reset

```sh
docker compose down
```

This keeps gateway routes and operational state in the named `gateway-data` volume.
For a fresh demonstration, the following command also removes that demo volume:

```sh
docker compose down --volumes
```

## Scope of v0.1.0

- The demo pins the broker/plugin image by digest. It contains Mosquitto **2.0.15**.
  Its upstream [mosquitto-go-auth project](https://github.com/iegomez/mosquitto-go-auth)
  is archived. This combination is for local evaluation, not a production recipe.
- The MQTT listener has no TLS. Public example credentials and demo policies must
  not be used for a public deployment.
- Tests cover this broker/image and MQTT 5. EMQX and VerneMQ adapters have HTTP
  contract tests; this release does not certify their real-broker interoperability.
- This demo uses an isolated Compose network and volume. It does not configure
  an existing external broker or systemd service.

Project-owned code is available under [GNU AGPL-3.0](../LICENSE).
External images and their components retain their original licenses.
