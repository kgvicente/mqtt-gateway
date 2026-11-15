# Contribuciones

Los cambios se proponen mediante pull requests hacia main.
@kgvicente es el responsable de revisar todos los archivos, incluido CODEOWNERS.
Los cambios posteriores a una aprobación requieren una nueva revisión.

El código propio del proyecto está bajo GNU Affero General Public License v3.0 (AGPL-3.0).
Envía únicamente aportaciones que tengas derecho a compartir bajo esa licencia.
Conserva las atribuciones y licencias de cualquier componente de terceros.

Para mantener la seguridad del repositorio:
- Nunca incluyas contraseñas, tokens de Telegram, claves maestras ni credenciales reales en commits, archivos versionados, issues o pull requests.
- Las pruebas automatizadas y fixtures deben utilizar exclusivamente valores y claves ficticias de prueba con proveedores simulados (mocks locales).
- Las nuevas funciones que involucren secretos deben seguir el modelo *write-only* con almacenamiento cifrado y no exponer material sensible en respuestas, DOM, atributos ni registros de auditoría.

Enviar una contribución no transfiere automáticamente su titularidad ni concede
al mantenedor derechos adicionales de explotación comercial. Cualquier permiso
comercial adicional sobre una aportación debe acordarse con su titular.

Para consultar licencias comerciales del código propio, contacta con
[@kgvicente](https://github.com/kgvicente).
