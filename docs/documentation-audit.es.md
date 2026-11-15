# Auditoría Exhaustiva de Documentación y Secretos Administrados (V4)

**Fecha:** 15 de noviembre de 2026

**Agente responsable:** `gateway-ui` (`w2:p6`)

**Repositorio:** `mqtt-gateway`

**Referencia de inventario:** `target/managed-documentation-inventory.txt` (28 archivos canónicos) + páginas web servidas (`docs.html`, `index.html`)

> [!NOTE]
> **Registro histórico de auditoría y Fuente de Verdad:** Este informe documenta el proceso y las verificaciones ejecutadas durante la auditoría. La **única fuente de verdad y autoridad técnica** del gateway es el código fuente del repositorio ([AdminResource.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/AdminResource.java), [GatewayState.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/GatewayState.java), [StorageService.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/StorageService.java), [GatewayConfig.java](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/java/pe/gateway/GatewayConfig.java) y [application.yml](https://github.com/kgvicente/mqtt-gateway/blob/main/src/main/resources/application.yml)). Los planes e informes de auditoría son memorias históricas del proceso de diseño e implementación y no poseen autoridad normativa frente al código fuente.

---

## 1. Resumen Ejecutivo de la Auditoría

En cumplimiento del contrato de **Secretos Administrados desde el Panel (V4)** y las directrices de orquestación, se realizó una auditoría integral archivo por archivo sobre todo el corpus documental del repositorio, incluyendo directorios ocultos (`.agents/`, `.github/`), espejos servidos en `/gateway/` y la interfaz de usuario web (`index.html`).

### Principales aspectos técnicos auditados y resueltos:
1. **Contrato de Secretos Write-Only (Panel y API):**
   - `routes.apiKey`: write-only en POST y PUT. Omitido conserva la clave actual; cadena vacía (`""`) la elimina; cadena no vacía establece o reemplaza la clave (máximo 4096 caracteres, caracteres válidos de cabecera HTTP sin CRLF). GET expone únicamente `hasApiKey: boolean`.
   - `settings.telegram.botToken`: write-only en body JSON. Omitido conserva; cadena vacía (`""`) elimina el token; cadena no vacía establece el token. GET expone `tokenConfigured: boolean`.
   - Eliminación integral de `apiKeyEnv` y de selectores de origen en la interfaz. El envío de `apiKeyEnv` con valor no vacío se documenta como rechazado con **HTTP 400**.
   - Limpieza inmediata del DOM: `apiKey.value = ''` y `telegramBotToken.value = ''` al guardar con éxito, al cancelar, al pulsar Escape o al cerrar los modales mediante listeners nativos `addEventListener('close', ...)`. En caso de error de red o HTTP 400, los valores tecleados se preservan temporalmente en pantalla para permitir la corrección por el operador sin obligarlo a reingresarlos.

2. **Criptografía Directa y Clave Maestra de Infraestructura (`GATEWAY_MASTER_KEY`):**
   - El motor utiliza `SecretKeySpec(keyBytes, "AES")` directamente con la clave maestra de 32 bytes (256 bits en Base64 estándar) para cifrado simétrico autenticado `AES/GCM/NoPadding` (sin funciones KDF intermedias derivadas).
   - `compose.yaml` ya define `${GATEWAY_MASTER_KEY:?...}`, por lo que no se requieren overrides de Compose.

3. **Procedimiento Seguro de `master.env` Fuera del Repositorio:**
   - La afirmación previa de que `master.env` estaba cubierto por `.gitignore` fue identificada como inexacta (las reglas `.env` / `.env.*` no coinciden con `master.env`).
   - Se estableció el procedimiento canónico para almacenar `master.env` **exclusivamente fuera del repositorio**:
     - **Linux / macOS (Bash):** `master_env_path="${XDG_CONFIG_HOME:-$HOME/.config}/mqtt-gateway/master.env"`
     - **Windows (PowerShell):** `$masterEnvPath = Join-Path $env:USERPROFILE '.config\mqtt-gateway\master.env'`
   - Se implementó la restricción estricta de permisos:
     - En Bash: directorio con modo `700`, archivo con `umask 077` y modo `600`.
     - En PowerShell: creación del directorio restringido al usuario actual con deshabilitación de herencia (`SetAccessRuleProtection($true, $false)`), generación mediante `[System.Security.Cryptography.RandomNumberGenerator]`, y escritura mediante `FileStream` con `FileMode.CreateNew` para garantizar exclusividad y evitar cualquier sobrescritura accidental.
   - En todos los comandos de Docker (`run`, `compose up`, `test`, `exec`, `down`), se utiliza explícitamente `--env-file "$master_env_path"` o `--env-file $masterEnvPath` y montaje de volumen persistente (`-v gateway-data:/app/data`), erradicando la generación efímera de claves por ejecución y la impresión de secretos en `stdout` o consola.

4. **Sincronización Canónica de Espejos y Resolución de Enlaces:**
   - Los 6 pares de espejos documentales se encuentran sincronizados byte a byte (`git diff --no-index` devuelve exit code 0).
   - Todos los enlaces locales relativos en los 28 documentos del inventario y páginas web fueron validados programáticamente, resolviendo 0 enlaces rotos.

---

## 2. Inventario Detallado Archivo por Archivo (28 archivos canónicos + UI)

| # | Archivo | Rol / Propósito | Estado | Detalle de Verificación y Modificación |
|---|---|---|---|---|
| 1 | `README.es.md` | Descripción principal del proyecto (Español) | **Modificado** | Se actualizó el aviso a texto durable distinguiendo el checkout actual, explicitando que la imagen del registro (`ghcr.io/kgvicente/mqtt-gateway:latest`) se actualiza únicamente tras el pipeline de CI sobre `main`, y estableciendo la compilación local (`docker build -t mqtt-gateway:local .` y `docker compose up --build`) con `--env-file "$master_env_path"` fuera del repositorio y volumen persistente `-v gateway-data:/app/data` para reproducir fielmente el código del checkout. Se incorporó `--env-file "$master_env_path"` / `$masterEnvPath` en todos los comandos de Docker Compose y se actualizó la descripción de secretos administrados eliminando `apiKeyEnv`. |
| 2 | `MANUAL_OPERACIONES.md` | Manual de operaciones, administración y despliegue | **Modificado** | Se corrigió la contradicción legal en la introducción (decía «licencia no comercial», alineándolo con GNU AGPLv3 declarada en `LICENSE` y `NOTICE`). En la sección 3 se sustituyó la variable de entorno a `GATEWAY_MASTER_KEY` para systemd. En la sección 3.5 se documentó el respaldo separado de la clave maestra y la base de datos SQLite WAL, la imposibilidad de rotación automática en UI, el fallo de arranque por discrepancia de clave (canary) y el requerimiento de backup previo para rollback a V3/V2. |
| 3 | `docs/three-phases.es.md` | Especificación de arquitectura y evolución por fases | **Modificado** | Se actualizó el diagrama de arquitectura Mermaid (Fase 1 con SQLite WAL + tabla secrets cifrada con AES-256-GCM y clave maestra `GATEWAY_MASTER_KEY`, hot path de evaluación en memoria RAM sin descifrado SQL por paquete MQTT). Se actualizó la sección de Telegram detallando el contrato atómico de `telegram.botToken` write-only y `tokenConfigured`. Se acotaron las descripciones de fallos al umbral predeterminado (`failureThreshold: 3`), estados de la API (`Normal`, `Degraded`, `Offline`) y se actualizó el conteo de pruebas a 35 JUnit (12 monitoreo, 14 outbox/SQLite, 9 secretos administrados). |
| 4 | `docs/telegram-docker.es.md` | Guía de integración de Telegram y despliegue Docker | **Modificado** | Se reestructuró la Sección 2 para crear `master.env` **fuera del repositorio** (`${XDG_CONFIG_HOME:-$HOME/.config}/mqtt-gateway/master.env` en Bash y `$env:USERPROFILE\.config\mqtt-gateway\master.env` en PowerShell) con permisos restrictivos (600 / ACL privado sin herencia y `FileMode.CreateNew`). Se corrigió la explicación criptográfica (AES directo sin derivación KDF) y se eliminaron requerimientos de overrides en `compose.yaml`. Se eliminó cualquier impresión de secretos en `stdout`. Se actualizó el conteo de pruebas automatizadas a 35 JUnit actuales (12 monitoreo, 14 outbox, 9 secretos) aclarando que corren sobre mocks y que la prueba real en Telegram BotFather/chat continúa pendiente. |
| 5 | `docs/reference.es.md` | Referencia técnica de adaptadores, API y persistencia | **Modificado** | Se documentaron exhaustivamente los contratos write-only de `apiKey` y `botToken` (caracteres ASCII imprimibles sin controles 0x20-0x7E y caracteres seguros `[A-Za-z0-9:_-]`), las banderas de lectura `hasApiKey` y `tokenConfigured`, y el rechazo con HTTP 400 de `apiKeyEnv`. Se corrigieron los enlaces canónicos relativos hacia GitHub para documentos no servidos por el endpoint `/gateway/`. Se actualizó la validación a 35 pruebas JUnit automatizadas. |
| 6 | `docs/quickstart.md` | Guía de inicio rápido (Inglés) | **Modificado** | Se sustituyó la clave efímera de shell por la carga desde el archivo privado persistido `master.env` fuera del repositorio (`set -a; . "$master_env_path"; set +a` en Bash y lectura segura en PowerShell sin imprimir secretos), preservando el acceso a través de sesiones y garantizando que el canary criptográfico valide el arranque con SQLite existente. |
| 7 | `docs/quickstart.es.md` | Guía de inicio rápido (Español) | **Modificado** | Se sustituyó la generación efímera en shell por la carga desde el archivo privado persistido `master.env` fuera del repositorio (`set -a; . "$master_env_path"; set +a` en Bash y lectura segura en PowerShell sin imprimir la clave), preservando la clave entre sesiones y garantizando que el canary criptográfico valide el arranque con SQLite existente. |
| 8 | `docs/managed-secrets-plan.es.md` | Plan y contrato de secretos administrados (Root) | **Revisado (No aplica)** | Documento canónico propiedad exclusiva del orquestador (`root`). Se verificó para alineación estricta de interfaces y reglas, sin realizar modificaciones en el archivo conforme a las restricciones del rol. |
| 9 | `docs/llms.txt` | Resumen de especificación para agentes de IA | **Modificado** | Se actualizaron los endpoints administrativos (`POST/PUT /api/admin/routes`, `GET/PUT /api/admin/settings`), documentando el almacenamiento cifrado AES-256-GCM en SQLite con `GATEWAY_MASTER_KEY`, los campos write-only `apiKey` y `botToken`, las banderas `hasApiKey` y `tokenConfigured`, y el rechazo de `apiKeyEnv` con HTTP 400. |
| 10 | `docs/llms-full.txt` | Especificación técnica consolidada para agentes de IA | **Modificado** | Sincronizado integralmente con `reference.es.md`. Se actualizaron contratos de API, persistencia en SQLite, nonces de 12 bytes y AAD por propietario, eliminación de `apiKeyEnv` y enlaces canónicos de repositorio. |
| 11 | `docs/implementation-plan.es.md` | Plan histórico de fases F1-F3 | **Modificado** | Se antepuso una nota de superación histórica formal aclarando que el contrato de referencias externas `apiKeyEnv` y token de entorno ha sido sustituido por el modelo de secretos administrados en SQLite, enlazando canónicamente a `docs/managed-secrets-plan.es.md`. |
| 12 | `.github/skills/mqtt-gateway/SKILL.md` | Habilidad para agentes de IA (Mirror GitHub) | **Modificado** | Espejo exacto de `.agents/skills/mqtt-gateway/SKILL.md`. Rutas de enlaces Markdown corregidas a `../../../docs/...`. Se actualizó la descripción de la base de datos (secretos cifrados con AES-256-GCM) y se acotó el comportamiento de sondeo de disponibilidad a la acumulación de fallos hasta `failureThreshold` (por defecto 3). |
| 13 | `.github/SECURITY.md` | Política de seguridad y reporte de vulnerabilidades | **Revisado** | Verificado. Contiene canales de divulgación responsable privada en GitHub Advisories, tratamiento de componentes críticos de autenticación y requisitos de redacción de credenciales sensibles. No requirió modificaciones. |
| 14 | `.github/pull_request_template.md` | Plantilla para Pull Requests de GitHub | **Modificado** | Se corrigió el enlace hacia `../CONTRIBUTING.md` y se actualizó el comando de verificación de Docker Compose en la lista de chequeo para incorporar `--env-file master.env`. |
| 15 | `.github/CODE_OF_CONDUCT.md` | Código de conducta de la comunidad | **Revisado** | Verificado. Adhiere al estándar Contributor Covenant v2.0. No requirió modificaciones. |
| 16 | `docs/images/README.md` | Guía de generación de capturas de documentación | **Revisado** | Verificado. Describe el uso de Playwright y `scripts/capture_docs.py` en loopback con datos ilustrativos sin credenciales reales. No requirió modificaciones. |
| 17 | `.github/ACCESSIBILITY.md` | Declaración y directrices de accesibilidad | **Modificado** | Se corrigió el enlace relativo roto hacia `../docs/llms.txt`. |
| 18 | `docs/compose.md` | Guía de despliegue con Docker Compose (Inglés) | **Modificado** | Se reemplazó la generación de clave efímera y `Get-Random` por el procedimiento de archivo privado `master.env` fuera del repositorio enlazando a la guía de Telegram. Se incorporó `--env-file "$master_env_path"` y `--env-file $masterEnvPath` en todos los comandos de Docker Compose (`up`, `test`, `exec`, `down`). |
| 19 | `docs/compose.es.md` | Guía de despliegue con Docker Compose (Español) | **Modificado** | Se documentó la preparación de `master.env` fuera del repositorio reutilizable con `$masterEnvPath` / `master_env_path` en todos los comandos de Compose (`up`, `test`, `exec`, `down`), garantizando la preservación de la clave maestra y el volumen `gateway-data`. |
| 20 | `CONTRIBUTING.md` | Guía para contribuyentes del proyecto | **Modificado** | Se añadieron directrices explícitas sobre la gestión de credenciales y secretos: prohibición estricta de incluir tokens, contraseñas o claves maestras reales en código o commits; uso obligatorio de credenciales ficticias y de `GATEWAY_MASTER_KEY` efímera en pruebas locales; respeto al modelo write-only y al cifrado AES-256-GCM. Se actualizó la referencia de licencia a AGPL-3.0. |
| 21 | `CHANGELOG.md` | Registro de cambios del proyecto | **Modificado** | Se añadió la sección `[Unreleased]` documentando la arquitectura V4: secretos administrados desde el panel con cifrado AES-256-GCM y clave maestra obligatoria, contratos write-only `apiKey` y `botToken`, flags `hasApiKey` y `tokenConfigured`, eliminación de `apiKeyEnv`, outbox transaccional de Telegram F3, histéresis F2 y alineación de licencia AGPLv3. |
| 22 | `src/main/resources/META-INF/resources/gateway/three-phases.es.md` | Espejo servido de arquitectura por fases | **Modificado** | Espejo idéntico (diff 0) de `docs/three-phases.es.md`. |
| 23 | `src/main/resources/META-INF/resources/gateway/telegram-docker.es.md` | Espejo servido de la guía de Telegram y Docker | **Modificado** | Espejo idéntico (diff 0) de `docs/telegram-docker.es.md`. |
| 24 | `src/main/resources/META-INF/resources/gateway/llms.txt` | Espejo servido de especificación para agentes | **Modificado** | Espejo idéntico (diff 0) de `docs/llms.txt`. |
| 25 | `src/main/resources/META-INF/resources/gateway/llms-full.txt` | Espejo servido de especificación técnica completa | **Modificado** | Espejo idéntico (diff 0) de `docs/llms-full.txt`. |
| 26 | `src/main/resources/META-INF/resources/gateway/docs.md` | Espejo servido de la referencia técnica | **Modificado** | Espejo idéntico (diff 0) de `docs/reference.es.md`. |
| 27 | `README.md` | Descripción principal del proyecto (Inglés) | **Modificado** | Se actualizó el aviso a texto durable distinguiendo el checkout actual, explicitando que la imagen del registro (`ghcr.io/kgvicente/mqtt-gateway:latest`) se actualiza únicamente tras el pipeline de CI sobre `main`, y estableciendo la compilación local (`docker build -t mqtt-gateway:local .` y `docker compose up --build`) con `--env-file "$master_env_path"` fuera del repositorio y volumen persistente `-v gateway-data:/app/data` para reproducir fielmente el código del checkout. Se incorporó `--env-file "$master_env_path"` / `$masterEnvPath` en todos los comandos de Docker Compose y se actualizó la descripción de secretos administrados eliminando `apiKeyEnv`. |
| 28 | `.agents/skills/mqtt-gateway/SKILL.md` | Habilidad para agentes de IA (.agents) | **Modificado** | Enlaces relativos a `docs/` ajustados a `../../../docs/...`. Se actualizaron contratos write-only, requerimiento de `GATEWAY_MASTER_KEY` (32 bytes Base64), eliminación de `apiKeyEnv`, y aclaración de apertura de incidentes al alcanzar `failureThreshold` (por defecto 3). |
| + | `src/main/resources/META-INF/resources/gateway/docs.html` | Página web servida de documentación técnica | **Modificado** | **Alineación estricta y corrección semántica (`DOCS_FIX_2`):** se incorporó la sección visible `<section id="notificaciones">` y su enlace en la tabla de contenidos (sidebar desktop y details mobile), documentando eventos automáticos, pasos de BotFather, persistencia en outbox, guardado atómico, reintentos con backoff, TTL y retención. En la tabla de endpoints de la API administrativa (`/api/admin/...`), se retiraron los 4 endpoints falsos inexistentes en el backend (`GET /routes/{id}`, `POST /routes/{id}/pause`, `POST /routes/{id}/resume`, `POST /incidents/{id}/acknowledge`), documentando en su lugar que la pausa y reanudación se gestionan mediante `PUT /api/admin/routes/{id}` con la configuración completa y `enabled: false/true`, que `GET /routes` devuelve configuración, estado activo/pausado y valores resueltos `effectiveMonitoring`/`effectiveTelegram` (no métricas agregadas), y que `GET /metrics` reporta las métricas de latencia y disponibilidad por backend. En la sección de códigos de error se corrigió la fila `HTTP 5xx` (el tráfico auth/ACL nunca abre incidentes, función exclusiva de `/health` con histéresis) y se clarificó la fila `Timeout` (`gateway.timeout-ms` por defecto 2 000 ms independiente de `probeTimeoutMs`). Todos los botones de copia y enlaces internos validados. |
| + | `src/main/resources/META-INF/resources/gateway/index.html` | Panel administrativo del gateway | **Modificado** | Implementación completa de la UI V4: inputs de tipo password (`autocomplete="new-password"`) para `apiKey` y `botToken`; omisión conserva credencial, cadena vacía elimina; GET muestra badges según `hasApiKey` y `tokenConfigured`; eliminación atómica de token exigiendo `enabled: false`; listeners nativos `close` en `#route-dialog` y `#settings-dialog` para limpieza total de DOM al cerrar/cancelar/Escape; preservación de valores tecleados ante error de red o HTTP 400. Sintaxis JS validada sin errores. Archivo congelado. |

---

## 3. Verificación de Espejos Canónicos y Referencia Técnica Cuádruple

Se ejecutó la comprobación de paridad mediante `git diff --no-index` para cada uno de los 6 pares canónicos, confirmando **diferencia nula (diff 0, exit code 0)** en todos ellos:

1. `docs/reference.es.md` $\equiv$ `src/main/resources/META-INF/resources/gateway/docs.md`
2. `docs/telegram-docker.es.md` $\equiv$ `src/main/resources/META-INF/resources/gateway/telegram-docker.es.md`
3. `docs/three-phases.es.md` $\equiv$ `src/main/resources/META-INF/resources/gateway/three-phases.es.md`
4. `docs/llms.txt` $\equiv$ `src/main/resources/META-INF/resources/gateway/llms.txt`
5. `docs/llms-full.txt` $\equiv$ `src/main/resources/META-INF/resources/gateway/llms-full.txt`
6. `.agents/skills/mqtt-gateway/SKILL.md` $\equiv$ `.github/skills/mqtt-gateway/SKILL.md`

### Séptimo Contrato Previo: Sincronización Cuádruple de la Referencia Técnica Completa
Conforme al contrato de paridad documental entre la referencia completa y la especificación íntegra para LLMs, los cuatro archivos siguientes son **idénticos byte por byte**:
- `docs/reference.es.md`
- `docs/llms-full.txt`
- `src/main/resources/META-INF/resources/gateway/docs.md`
- `src/main/resources/META-INF/resources/gateway/llms-full.txt`

**Suma criptográfica SHA-256 verificada (idéntica en los 4 archivos tras incorporar la nota de código fuente como única fuente de verdad y retirar el endpoint GET inexistente):**

`DF9B6A2D6370E9DF597568FBFD1C3DD1BD1CBD9B7BD26FB25D7C7F475640D322`

---

## 4. Validación Local de Archivos y Sintaxis

- **Nota de Corrección Honesta y Cobertura Concreta (`DOCS_FIX_2`):** Se subsanó la limitación de la auditoría anterior donde la página `docs.html` carecía semánticamente de la sección visible de notificaciones y de la tabla de endpoints de administración presentes en el Markdown. En esta revisión (`DOCS_FIX_2`), se sustituyó la asunción de paridad previa por una **cobertura concreta de notificaciones y endpoints reales** verificados directamente contra la implementación en `AdminResource.java`: se eliminaron de la tabla los cuatro endpoints que no existen en el backend (`GET /api/admin/routes/{id}`, `POST /api/admin/routes/{id}/pause`, `POST /api/admin/routes/{id}/resume` y `POST /api/admin/incidents/{id}/acknowledge`), documentando que la pausa y reanudación se gestionan mediante `PUT /api/admin/routes/{id}` (o `/routes`) con la configuración completa de la ruta y `enabled: false/true`, que `GET /api/admin/routes` devuelve la configuración, el estado activo/pausado y los valores resueltos `effectiveMonitoring` y `effectiveTelegram` (sin métricas agregadas), y que `GET /api/admin/metrics` provee las métricas por backend. La validación visual interactiva se completó por HTTP real en escritorio y móvil; el cierre posterior al despliegue se registra al final de este informe.
- **Validación Local de Enlaces:** El análisis automatizado sobre los 28 archivos del inventario y las páginas HTML servidas confirmó que **todos los enlaces locales y relativos resuelven correctamente contra archivos existentes en disco (0 enlaces locales rotos)**. Esta verificación es estrictamente local sobre el sistema de archivos del repositorio; los enlaces externos a internet (como GitHub, Telegram API o Docker Hub) no fueron comprobados mediante peticiones de red ya que la ejecución opera en entorno local sin navegación web.
- **Contratos Criptográficos y Caracteres Seguros Congelados:** Se incorporó en toda la documentación la especificación definitiva del backend:
  - `apiKey`: hasta 4 096 caracteres, exclusivamente caracteres ASCII imprimibles sin controles (rango 0x20 a 0x7E, sin saltos de línea CRLF).
  - `telegram.botToken`: exclusivamente caracteres seguros `[A-Za-z0-9:_-]`, no vacío.
- **Protección de Concurrencia y Fallos en Bash:** En la guía de Telegram, se corrigió la subshell de creación de `master.env` utilizando asignación previa independiente (`master_key=$(openssl rand -base64 32) || exit 1`), verificación explícita de cadena no vacía (`[ -n "$master_key" ] || exit 1`), escritura protegida con `set -o noclobber` y `printf 'GATEWAY_MASTER_KEY=%s\n' "$master_key" > "$master_env_path" || exit 1`, y limpieza inmediata (`unset master_key`). Las llamadas a `mkdir` y `chmod` cuentan con verificación `|| exit 1`. Si `openssl` o la escritura fallan, la subshell termina con error hacia `stderr` sin crear un archivo vacío ni imprimir indebidamente que fue generado.
- **Pruebas de Validación Real por Orquestador (Root):** El orquestador ejecutó y validó en entorno real los snippets de ambas plataformas:
  - **Bash en Linux (Docker sin red):** Ejecutado mediante script con saltos de línea LF montado (aislado de CRLF de Windows), verificando:
    1. Generación normal de clave de 32 bytes en Base64 con permisos `600` (`NORMAL_GENERATION_32B_600_REUSE=true`).
    2. Reejecución que detecta y conserva la clave existente sin alterarla ni sobrescribirla.
    3. Manejo de fallo de `openssl` simulado (`OPENSSL_FAILURE_CREATED_NO_KEY=true`), que aborta sin crear archivo de clave vacío y canaliza el error a `stderr`.
    *Evidencia registrada en:* `target/doc-managed-secrets-guide-proof.log` (`exitcode=0`).
  - **PowerShell en Windows:** Generación criptográfica mediante `RandomNumberGenerator` (32 bytes Base64), creación del directorio privado con ACL exclusiva sin herencia y reejecución que preserva la clave original.
  - *Límites de prueba:* Se deja constancia de que no se realizaron peticiones a redes externas ni se utilizaron credenciales reales de Telegram (la prueba con BotFather y chat real continúa pendiente).
- **Vinculación a Loopback en Docker Run:** En los ejemplos de `docker run` de ambos READMEs, el mapeo de puertos se restringió a la interfaz de loopback (`-p 127.0.0.1:18085:8085`), asegurando que la demostración no quede expuesta en todas las interfaces de red del host.
- **Sintaxis JavaScript e Interfaz Congelada:** Los bloques `<script>` de `index.html` fueron evaluados mediante el motor V8 de Node.js, validando una compilación limpia sin advertencias ni errores de sintaxis. El archivo `index.html` queda formalmente congelado para la validación de QA mediante navegador.
- **Alcance del Rol:** No se alteraron archivos Java, SQL, POM, Docker Compose base, scripts de prueba ni fixtures de QA, preservando la estricta delimitación de titularidad de `gateway-ui`.

## 5. Cierre de la corrección de la página Docs — 15 de noviembre de 2026

La comprobación anterior de copias y enlaces no había detectado la falta de una sección visible de notificaciones en el HTML. Esta corrección añade `#notificaciones` al índice y al contenido, con configuración desde Ajustes, credenciales write-only, herencia por backend, eventos automáticos, outbox, reintentos, estados, historial y prueba HTTP 202. El catálogo administrativo fue contrastado directamente con las anotaciones de `AdminResource.java`: 14 filas, ningún par método/ruta inexistente. No se afirma que el HTML reproduzca todos los documentos complementarios del repositorio.

La primera captura de escritorio estaba vacía y fue descartada. La aceptación utiliza navegación HTTP real y verifica que el título siga visible después de pulsar el enlace del índice. Se corrigieron los ejemplos de `nextAttemptAt` y se actualizaron a 35 las pruebas JUnit actuales.

- Imagen desplegada: `sha256:e9f32e8da7e94cabd20513599a6abc69f052eb41c4d66254a76cbfd315e2c426`.
- URL verificada: <http://127.0.0.1:18085/gateway/docs#notificaciones>.
- Navegador posterior al despliegue: escritorio 1440×900 y móvil 390×844; título visible tras navegar desde el índice, cero errores JavaScript.
- Evidencia local: `target/docs-notificaciones-live-desktop.png`, `target/docs-notificaciones-live-mobile.png` y `target/qa-docs-docker-build-final.log` (build con salida 0). El helper de revisión quedó en `target/`; no se incorporaron pruebas permanentes por este cambio documental.
- Los bytes de `docs.html`, `docs.md` y `llms-full.txt` servidos por la demo coinciden por SHA-256 con sus fuentes. Readiness `UP`.
- Se preservaron volumen y clave maestra. Respaldo privado previo con el servicio detenido: `%USERPROFILE%\.config\mqtt-gateway-preview\backup-before-docs-20261115-164407`. Imagen anterior conservada como `mqtt-gateway:preview-before-docs-notificaciones`.
- Esta revisión no modificó Java, SQL, `index.html` ni ajustes del panel; tampoco realizó envíos reales a Telegram.