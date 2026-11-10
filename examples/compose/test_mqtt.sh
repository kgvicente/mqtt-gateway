#!/bin/sh
set -eu

# ponytail: usar los clientes del propio bróker prueba MQTT sin otra biblioteca.
work=$(mktemp -d)
subscriber=''
cleanup() {
  if [ -n "$subscriber" ]; then kill "$subscriber" 2>/dev/null || true; fi
  rm -f "$work/message" "$work/denied" "$work/subscriber"
  rmdir "$work"
}
trap cleanup EXIT INT TERM

mosquitto_sub -h mosquitto -V mqttv5 -i demo_sub -u demo-user -P demo-password \
  -t demo/temperature -q 1 -C 1 -W 12 >"$work/message" 2>"$work/subscriber" &
subscriber=$!
sleep 1
mosquitto_pub -h mosquitto -V mqttv5 -i demo_pub -u demo-user -P demo-password \
  -t demo/temperature -q 1 -m mqtt-gateway-demo-ok
wait "$subscriber"
subscriber=''
grep -qx mqtt-gateway-demo-ok "$work/message"
printf '%s\n' 'CORRECTO: publicación, suscripción y entrega real del mensaje.'

if mosquitto_pub -h mosquitto -V mqttv5 -d -i demo_bad_password -u demo-user -P incorrecta \
  -t demo/temperature -q 1 -m denied >"$work/denied" 2>&1; then
  printf '%s\n' 'ERROR: se aceptó una contraseña incorrecta.' >&2; exit 1
fi
grep -Eq 'received CONNACK \(13[45]\)' "$work/denied"
printf '%s\n' 'CORRECTO: credenciales incorrectas rechazadas.'

# ponytail: este cliente devuelve cero ante ciertos rechazos; verificar el ACK MQTT real.
mosquitto_pub -h mosquitto -V mqttv5 -d -i demo_denied_publish -u demo-user -P demo-password \
  -t demo/private/secret -q 1 -m denied >"$work/denied" 2>&1 || true
grep -q 'received CONNACK (0)' "$work/denied"
grep -Eq 'received PUBACK .*RC:135' "$work/denied"
printf '%s\n' 'CORRECTO: publicación no autorizada rechazada.'

mosquitto_sub -h mosquitto -V mqttv5 -d -i demo_denied_subscribe -u demo-user -P demo-password \
  -t demo/private/secret -q 1 -E -W 5 >"$work/denied" 2>&1 || true
grep -q 'received CONNACK (0)' "$work/denied"
grep -Eq 'Subscribed .*: 135' "$work/denied"
printf '%s\n' 'CORRECTO: suscripción no autorizada rechazada.'
