# Capturas de documentación

Las imágenes muestran la UI real con datos HTTP ilustrativos. No proceden de
producción ni representan pruebas de rendimiento o de interoperabilidad MQTT.

Para regenerarlas desde la raíz del repositorio:

```sh
python -m pip install playwright
python -m playwright install chromium
python scripts/capture_docs.py
```

El script sirve los archivos estáticos en loopback, simula las respuestas
administrativas en el navegador y cierra su servidor al terminar. No utiliza
credenciales reales ni modifica servicios externos. Playwright solo es necesario
para generar estas imágenes; no es una dependencia de producción del gateway.
