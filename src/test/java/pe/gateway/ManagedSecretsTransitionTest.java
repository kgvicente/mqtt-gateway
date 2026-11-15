package pe.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pruebas unitarias puras y deterministas para Secretos Administrados, Cifrado AES-256-GCM y Canary en SQLite:
 * - Configuración mediante GatewayConfig.masterKey() (sin métodos estáticos de bypass global).
 * - Obligatoriedad de MASTER_KEY (exactamente 32 bytes en Base64).
 * - Creación y verificación de Canary al arrancar V4.
 * - Rechazo de arranque con clave maestra incorrecta sobre base existente.
 * - Nonce aleatorio fresco de 12 bytes por cifrado (mismo plaintext genera ciphertexts distintos).
 * - AAD ligado al propietario (owner) y versión:
 *   * Demuestra descifrado correcto bajo owner2 del envelope completo (version, nonce2, cipher2).
 *   * Demuestra que al copiar el envelope completo a owner1, el arranque falla exclusivamente por discrepancia AAD.
 * - Tamper de nonce, ciphertext y versión detectados al arrancar antes de publicar caché.
 * - Atomicidad estricta y rollback ante fallo SQLite (triggers): DB y caché conservan estado previo sin corrupción parcial.
 * - Ciclo de vida write-only: almacenamiento seguro, verificación con hasSecretSync, preservación y borrado atómico.
 * - Rechazo estricto de entradas inválidas de apiKey y botToken con verificación de no mutación.
 * - Cleanup riguroso de conexiones y escritores de StorageService en @AfterEach.
 */
public class ManagedSecretsTransitionTest {

    private static final String VALID_KEY_1 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="; // 32 bytes
    private static final String VALID_KEY_2 = "OTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTBmZWRjYmE="; // 32 bytes distinta
    private static final String SHORT_KEY = "MDEyMzQ1Njc4OWFiY2RlZg=="; // 16 bytes (inválida)

    private Path tempDir;
    private Path dbFile;
    private ObjectMapper json;
    private final List<StorageService> storagesToClean = new CopyOnWriteArrayList<>();

    private static GatewayConfig createFakeConfig(String dataDir, String masterKey) {
        return (GatewayConfig) Proxy.newProxyInstance(
                GatewayConfig.class.getClassLoader(),
                new Class<?>[]{GatewayConfig.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("dataDir")) return dataDir;
                    if (name.equals("timeoutMs")) return 2000L;
                    if (name.equals("probeSeconds")) return 30;
                    if (name.equals("masterKey")) return Optional.ofNullable(masterKey);
                    if (name.equals("telegram")) return Optional.empty();
                    if (method.getReturnType().equals(Optional.class)) return Optional.empty();
                    if (method.getReturnType().equals(List.class)) return List.of();
                    if (method.getReturnType().equals(Map.class)) return Map.of();
                    if (method.getReturnType().equals(boolean.class)) return false;
                    if (method.getReturnType().equals(int.class)) return 0;
                    if (method.getReturnType().equals(long.class)) return 0L;
                    return null;
                }
        );
    }

    private StorageService createStorage(String masterKey) {
        StorageService s = new StorageService();
        s.config = createFakeConfig(tempDir.toAbsolutePath().toString(), masterKey);
        s.json = json;
        storagesToClean.add(s);
        return s;
    }

    private ObjectNode createBaseRoute(String name, String prefix, String apiKey) {
        ObjectNode r = json.createObjectNode();
        r.put("name", name);
        r.put("enabled", true);
        r.put("authUrl", "http://127.0.0.1:19999/auth");
        r.put("aclUrl", "http://127.0.0.1:19999/acl");
        r.put("healthUrl", "http://127.0.0.1:19999/health");
        ArrayNode auth = r.putArray("authPrefixes");
        auth.add(prefix + "_");
        ArrayNode acl = r.putArray("aclPrefixes");
        acl.add(prefix + "/");
        if (apiKey != null) {
            r.put("apiKey", apiKey);
        }
        return r;
    }

    @BeforeEach
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory(Path.of("target"), "test-sqlite-secrets-");
        dbFile = tempDir.resolve("gateway.db");
        json = new ObjectMapper();
    }

    @AfterEach
    public void tearDown() {
        for (StorageService s : storagesToClean) {
            try {
                s.close(null, null);
            } catch (Exception ignored) {}
        }
        storagesToClean.clear();
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testMissingMasterKeyFailsStartup() {
        StorageService storage = createStorage(null);
        Assertions.assertThrows(IllegalStateException.class, storage::init,
                "StorageService debe rehusar inicializarse sin GATEWAY_MASTER_KEY configurada");
    }

    @Test
    public void testInvalidLengthMasterKeyFailsStartup() {
        StorageService storage = createStorage(SHORT_KEY);
        Assertions.assertThrows(Exception.class, storage::init,
                "StorageService debe rehusar inicializarse con una clave de longitud distinta de 32 bytes");
    }

    @Test
    public void testCanaryDetectsWrongMasterKeyOnExistingDb() throws Exception {
        // 1. Inicializar por primera vez con VALID_KEY_1
        StorageService storage1 = createStorage(VALID_KEY_1);
        storage1.init();

        // Verificar que el canary existe en SQLite con versión 1 y tamaños conformes
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT owner, version, nonce, ciphertext FROM managed_secrets WHERE owner = 'canary';");
                 ResultSet rs = ps.executeQuery()) {
                Assertions.assertTrue(rs.next(), "La fila canary debe existir en managed_secrets tras migración V4");
                Assertions.assertEquals("canary", rs.getString("owner"));
                Assertions.assertEquals(1, rs.getInt("version"), "Versión de formato debe ser 1");
                byte[] nonce = rs.getBytes("nonce");
                byte[] ciphertext = rs.getBytes("ciphertext");
                Assertions.assertNotNull(nonce);
                Assertions.assertNotNull(ciphertext);
                Assertions.assertEquals(12, nonce.length, "Nonce GCM estándar debe ser de 12 bytes");
                Assertions.assertTrue(ciphertext.length >= 16, "Ciphertext con tag GCM de 128 bits debe ser >= 16 bytes");
            }
        } catch (Exception e) {
            Assertions.fail("Fallo al verificar tabla managed_secrets: " + e.getMessage());
        }

        storage1.close(null, null);

        // 2. Intentar inicializar un segundo StorageService sobre la misma DB pero con VALID_KEY_2 (clave incorrecta)
        StorageService storage2 = createStorage(VALID_KEY_2);
        Assertions.assertThrows(IllegalStateException.class, storage2::init,
                "El canary debe detectar la clave maestra incorrecta y abortar el arranque con IllegalStateException");
    }

    @Test
    public void testAesGcmNoncesAndAadIsolationWithRowSwap() throws Exception {
        StorageService storage = createStorage(VALID_KEY_1);
        storage.init();

        GatewayState state = new GatewayState();
        state.json = json;
        state.storage = storage;
        state.config = storage.config;
        state.hostMetrics = new HostMetricsService();

        // Cifrar exactamente el MISMO plaintext para ambas rutas para aislar el efecto de nonces y AAD
        String sharedSecret = "secret-identical-plaintext-999";

        ObjectNode r1 = createBaseRoute("Route AAD 1", "aad1", sharedSecret);
        ObjectNode r2 = createBaseRoute("Route AAD 2", "aad2", sharedSecret);

        ObjectNode created1 = state.put(null, r1);
        String id1 = created1.path("id").asText();
        String owner1 = "backend:" + id1;

        ObjectNode created2 = state.put(null, r2);
        String id2 = created2.path("id").asText();
        String owner2 = "backend:" + id2;

        Assertions.assertTrue(storage.hasSecretSync(owner1));
        Assertions.assertTrue(storage.hasSecretSync(owner2));

        int ver1, ver2;
        byte[] nonce1, cipher1, nonce2, cipher2;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT version, nonce, ciphertext FROM managed_secrets WHERE owner = ?;")) {
                ps.setString(1, owner1);
                try (ResultSet rs = ps.executeQuery()) {
                    Assertions.assertTrue(rs.next());
                    ver1 = rs.getInt("version");
                    nonce1 = rs.getBytes("nonce");
                    cipher1 = rs.getBytes("ciphertext");
                }
                ps.setString(1, owner2);
                try (ResultSet rs = ps.executeQuery()) {
                    Assertions.assertTrue(rs.next());
                    ver2 = rs.getInt("version");
                    nonce2 = rs.getBytes("nonce");
                    cipher2 = rs.getBytes("ciphertext");
                }
            }
        }

        Assertions.assertEquals(1, ver1);
        Assertions.assertEquals(1, ver2);
        Assertions.assertEquals(12, nonce1.length);
        Assertions.assertEquals(12, nonce2.length);

        // Conservar prueba de que nonces aleatorios son independientes y mismo plaintext genera envelopes distintos
        Assertions.assertFalse(Arrays.equals(nonce1, nonce2), "Nonces aleatorios frescos deben ser distintos");
        Assertions.assertFalse(Arrays.equals(cipher1, cipher2), "Mismo plaintext con nonces distintos genera ciphertexts distintos");

        // DEMOSTRAR PRIMERO: El envelope 2 (version2, nonce2, cipher2) DESCIFRA CORRECTAMENTE bajo owner2
        String decrypted2 = storage.decryptSecret(ver2, owner2, nonce2, cipher2);
        Assertions.assertEquals(sharedSecret, decrypted2,
                "El envelope completo de owner2 debe descifrar exitosamente bajo su propio owner");

        // DEMOSTRAR PRIMERO: El envelope 1 (version1, nonce1, cipher1) DESCIFRA CORRECTAMENTE bajo owner1
        String decrypted1 = storage.decryptSecret(ver1, owner1, nonce1, cipher1);
        Assertions.assertEquals(sharedSecret, decrypted1,
                "El envelope completo de owner1 debe descifrar exitosamente bajo su propio owner");

        // PROBAR AAD REAL: Copiar el ENVELOPE COMPLETO (version2, nonce2, cipher2) de owner2 a owner1
        // Ahora owner1 tiene el nonce legítimo y ciphertext legítimo, pero al descifrar bajo owner1
        // el AAD computado será "v1:" + owner1 en vez de "v1:" + owner2
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE managed_secrets SET version = ?, nonce = ?, ciphertext = ? WHERE owner = ?")) {
                ps.setInt(1, ver2);
                ps.setBytes(2, nonce2);
                ps.setBytes(3, cipher2);
                ps.setString(4, owner1);
                int updated = ps.executeUpdate();
                Assertions.assertEquals(1, updated);
            }
        }

        storage.close(null, null);

        // Al intentar inicializar un nuevo StorageService que valida todos los secretos al arrancar,
        // DEBE abortar con IllegalStateException debido a tag GCM mismatch provocado EXCLUSIVAMENTE por AAD incorrecto
        StorageService storageSwap = createStorage(VALID_KEY_1);
        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, storageSwap::init,
                "El arranque debe abortar ante fallo de integridad de AAD provocado por row-swap del envelope completo");
        Assertions.assertTrue(ex.getMessage().contains(owner1) || ex.getMessage().contains("Integridad"),
                "El mensaje debe señalar la falta de integridad en el secreto del owner1 comprometido");
    }

    @Test
    public void testTamperNonceCipherAndVersionFailsStartup() throws Exception {
        StorageService storage = createStorage(VALID_KEY_1);
        storage.init();

        GatewayState state = new GatewayState();
        state.json = json;
        state.storage = storage;
        state.config = storage.config;
        state.hostMetrics = new HostMetricsService();

        ObjectNode r = createBaseRoute("Tamper Test", "tamp", "secreto-a-corromper");
        ObjectNode created = state.put(null, r);
        String owner = "backend:" + created.path("id").asText();

        // 1. Tamper de 1 byte en el ciphertext
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            byte[] cipher;
            try (PreparedStatement ps = conn.prepareStatement("SELECT ciphertext FROM managed_secrets WHERE owner = ?")) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    Assertions.assertTrue(rs.next());
                    cipher = rs.getBytes("ciphertext");
                }
            }
            cipher[0] = (byte) (cipher[0] ^ 0xFF); // invertir bits
            try (PreparedStatement ps = conn.prepareStatement("UPDATE managed_secrets SET ciphertext = ? WHERE owner = ?")) {
                ps.setBytes(1, cipher);
                ps.setString(2, owner);
                ps.executeUpdate();
            }
        }

        storage.close(null, null);

        StorageService storageTamperCipher = createStorage(VALID_KEY_1);
        Assertions.assertThrows(IllegalStateException.class, storageTamperCipher::init,
                "El arranque debe fallar inmediatamente si el ciphertext ha sido alterado (tamper)");

        // 2. Tamper de 1 byte en el nonce
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            byte[] nonce;
            try (PreparedStatement ps = conn.prepareStatement("SELECT nonce FROM managed_secrets WHERE owner = ?")) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    Assertions.assertTrue(rs.next());
                    nonce = rs.getBytes("nonce");
                }
            }
            nonce[0] = (byte) (nonce[0] ^ 0xFF); // invertir bits
            try (PreparedStatement ps = conn.prepareStatement("UPDATE managed_secrets SET nonce = ? WHERE owner = ?")) {
                ps.setBytes(1, nonce);
                ps.setString(2, owner);
                ps.executeUpdate();
            }
        }

        StorageService storageTamperNonce = createStorage(VALID_KEY_1);
        Assertions.assertThrows(IllegalStateException.class, storageTamperNonce::init,
                "El arranque debe fallar inmediatamente si el nonce ha sido alterado (tamper)");

        // 3. Probar que Java rechaza unknown version al descifrar al iniciar sobre DB adulterada
        // Se usa PRAGMA ignore_check_constraints = ON para bypass temporal del CHECK de SQLite y verificar el control en Java
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA ignore_check_constraints = ON;");
                st.executeUpdate("UPDATE managed_secrets SET version = 99 WHERE owner = '" + owner + "';");
            }
        }

        StorageService storageTamperVer = createStorage(VALID_KEY_1);
        Assertions.assertThrows(IllegalStateException.class, storageTamperVer::init,
                "El arranque debe fallar si la versión de cifrado en la tabla es desconocida o adulterada (control estricto en Java)");
    }

    @Test
    public void testAtomicRollbackOnSqliteTriggerFailure() throws Exception {
        StorageService storage = createStorage(VALID_KEY_1);
        storage.init();

        GatewayState state = new GatewayState();
        state.json = json;
        state.storage = storage;
        state.config = storage.config;
        state.hostMetrics = new HostMetricsService();

        // 1. Crear una ruta inicial con clave conocida
        ObjectNode r = createBaseRoute("Atomic Route", "atom", "initial-stable-key");
        ObjectNode created = state.put(null, r);
        String id = created.path("id").asText();
        String owner = "backend:" + id;

        Assertions.assertTrue(storage.hasSecretSync(owner));
        Assertions.assertEquals("initial-stable-key", storage.loadAllSecretsSync().get(owner));
        Assertions.assertEquals("initial-stable-key", state.matchRoute("atom_", true).apiKey());

        // 2. Instalar un TRIGGER en SQLite que simule un fallo catastrófico en managed_secrets al intentar INSERT OR REPLACE
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TRIGGER fail_secret_insert BEFORE INSERT ON managed_secrets " +
                          "BEGIN SELECT RAISE(ABORT, 'Simulated SQLite failure in managed_secrets'); END;");
            }
        }

        // 3. Intentar reemplazar la clave en state.put -> DEBE fallar con excepción
        ObjectNode updatePayload = created.deepCopy();
        updatePayload.put("name", "Attempted Corrupted Name");
        updatePayload.put("apiKey", "new-attempted-key-that-must-rollback");

        Assertions.assertThrows(Exception.class, () -> state.put(id, updatePayload),
                "La operación debe fallar por el trigger de SQLite");

        // 4. VERIFICAR ATOMICIDAD TRAS FALLO EN UPDATE:
        // - En DB: El nombre de la ruta NO debe haber cambiado a 'Attempted Corrupted Name'
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM routes WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    Assertions.assertTrue(rs.next());
                    Assertions.assertEquals("Atomic Route", rs.getString("name"),
                            "El nombre de la ruta debió hacer rollback y conservar el valor previo");
                }
            }
        }

        // - En DB: La clave en managed_secrets conserva el estado previo intacto
        Assertions.assertEquals("initial-stable-key", storage.loadAllSecretsSync().get(owner),
                "La clave descifrada en DB debe conservar el valor anterior tras el rollback");

        // - En memoria: La ruta en state.route(id) y en el caché live conservan el estado previo
        ObjectNode routeInMem = state.route(id);
        Assertions.assertEquals("Atomic Route", routeInMem.path("name").asText());
        Assertions.assertEquals("initial-stable-key", state.matchRoute("atom_", true).apiKey(),
                "El caché live en memoria debe conservar la clave anterior tras el rollback");

        // Eliminar el trigger fail_secret_insert
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement s = conn.createStatement()) {
                s.execute("DROP TRIGGER IF EXISTS fail_secret_insert;");
            }
        }

        // 5. PROBAR ATOMICIDAD EN BORRADO: Trigger DELETE en managed_secrets al borrar ruta
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TRIGGER fail_secret_delete BEFORE DELETE ON managed_secrets " +
                          "BEGIN SELECT RAISE(ABORT, 'Simulated SQLite failure on delete'); END;");
            }
        }

        Assertions.assertThrows(Exception.class, () -> state.delete(id),
                "El borrado de ruta debe abortar si falla la eliminación de su secreto en SQLite");

        // Verificar que ruta y secreto continúan existiendo tanto en DB como en memoria
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM routes WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    Assertions.assertTrue(rs.next());
                    Assertions.assertEquals(1, rs.getInt(1), "La ruta no debió borrarse de la DB tras el fallo del trigger");
                }
            }
        }
        Assertions.assertEquals("initial-stable-key", storage.loadAllSecretsSync().get(owner),
                "El secreto de la ruta debe permanecer intacto en SQLite tras el aborto del borrado");
        Assertions.assertEquals("Atomic Route", state.route(id).path("name").asText(),
                "La ruta debe permanecer en memoria tras el aborto del borrado");
        Assertions.assertEquals("initial-stable-key", state.matchRoute("atom_", true).apiKey(),
                "La clave de la ruta debe permanecer en el caché de memoria");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement s = conn.createStatement()) {
                s.execute("DROP TRIGGER IF EXISTS fail_secret_delete;");
            }
        }

        // 6. PROBAR ATOMICIDAD EN SETTINGS MIXTO (monitoring + telegram + token) CON TRIGGER INSERT
        ObjectNode initSettings = json.createObjectNode();
        ObjectNode monInit = json.createObjectNode();
        monInit.put("probeIntervalSeconds", 30).put("probeTimeoutMs", 2000).put("failureThreshold", 3).put("recoveryThreshold", 2);
        ObjectNode tgInit = json.createObjectNode();
        tgInit.put("enabled", true).put("chatId", "-100111").put("botToken", "initial_bot_token-123");
        initSettings.set("monitoring", monInit);
        initSettings.set("telegram", tgInit);

        state.updateSettings(initSettings);
        Assertions.assertEquals(30, state.settings().path("monitoring").path("probeIntervalSeconds").asInt());
        Assertions.assertEquals("-100111", state.settings().path("telegram").path("chatId").asText());
        Assertions.assertEquals("initial_bot_token-123", storage.loadAllSecretsSync().get("telegram:bot-token"));

        // Instalar trigger que aborte inserción de secretos en SQLite
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TRIGGER fail_token_insert BEFORE INSERT ON managed_secrets " +
                          "BEGIN SELECT RAISE(ABORT, 'Simulated SQLite failure on token insert'); END;");
            }
        }

        // Intentar actualización mixta que modifica monitoring, telegram y token a la vez
        ObjectNode badMixedSettings = json.createObjectNode();
        ObjectNode monBad = json.createObjectNode();
        monBad.put("probeIntervalSeconds", 60).put("probeTimeoutMs", 1000).put("failureThreshold", 5).put("recoveryThreshold", 3);
        ObjectNode tgBad = json.createObjectNode();
        tgBad.put("enabled", true).put("chatId", "-100222").put("botToken", "new_attempted_token-456");
        badMixedSettings.set("monitoring", monBad);
        badMixedSettings.set("telegram", tgBad);

        Assertions.assertThrows(Exception.class, () -> state.updateSettings(badMixedSettings),
                "La actualización mixta debe fallar y hacer rollback completo si falla la inserción del secreto");

        // Verificar que en DB NO hubo mutación parcial de monitoring, telegram ni token
        String monInDb = storage.loadSetting("monitoring");
        Assertions.assertTrue(monInDb.contains("\"probeIntervalSeconds\":30") || monInDb.contains("30"),
                "El monitoreo en DB debió hacer rollback al intervalo 30");
        String tgInDb = storage.loadSetting("telegram");
        Assertions.assertTrue(tgInDb.contains("-100111") && !tgInDb.contains("-100222"),
                "El telegram en DB debió hacer rollback y no persistir el nuevo chatId");
        Assertions.assertEquals("initial_bot_token-123", storage.loadAllSecretsSync().get("telegram:bot-token"),
                "El botToken en DB debe conservar el valor original tras el rollback");

        // Verificar que en memoria NO hubo mutación parcial
        Assertions.assertEquals(30, state.settings().path("monitoring").path("probeIntervalSeconds").asInt());
        Assertions.assertEquals("-100111", state.settings().path("telegram").path("chatId").asText());
        Assertions.assertTrue(state.isTelegramTokenConfigured());

        // Observar mediante FakeHttpClient que el despacho outbox utiliza el token original conservado en caché
        TelegramOutboxTransitionTest.FakeHttpClient fakeClient = new TelegramOutboxTransitionTest.FakeHttpClient();
        fakeClient.handler = req -> new TelegramOutboxTransitionTest.FakeHttpResponse<>(200, "{\"ok\":true}", req);
        state.setTelegramClientForTesting(fakeClient);

        state.createTestNotification(json.createObjectNode().put("message", "probe after failed rollback"));
        int dispatched = state.triggerOutboxDispatchForTesting();
        Assertions.assertTrue(dispatched > 0, "Debe despachar el mensaje de prueba encolado");
        Assertions.assertEquals(1, fakeClient.sentRequests.size());
        String reqUri = fakeClient.sentRequests.get(0).uri().toString();
        Assertions.assertTrue(reqUri.contains("/botinitial_bot_token-123/sendMessage"),
                "El despacho outbox debe usar el token previo ('initial_bot_token-123') conservado en caché tras rollback");
        Assertions.assertFalse(reqUri.contains("new_attempted_token-456"),
                "El token fallido ('new_attempted_token-456') NO debe haber mutado la caché");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement s = conn.createStatement()) {
                s.execute("DROP TRIGGER IF EXISTS fail_token_insert;");
            }
        }
    }

    @Test
    public void testWriteOnlyRouteSecretLifecycleAndDeletion() throws Exception {
        StorageService storage = createStorage(VALID_KEY_1);
        storage.init();

        GatewayState state = new GatewayState();
        state.json = json;
        state.storage = storage;
        state.config = storage.config;
        state.hostMetrics = new HostMetricsService();

        // 1. Registro con clave write-only
        ObjectNode r = createBaseRoute("Lifecycle Route", "life", "initial-secret-key-123");
        ObjectNode registered = state.put(null, r);
        String id = registered.path("id").asText();
        String owner = "backend:" + id;

        Assertions.assertTrue(registered.path("hasApiKey").asBoolean());
        Assertions.assertFalse(registered.has("apiKey"), "put no debe exponer apiKey en el resultado");
        Assertions.assertTrue(storage.hasSecretSync(owner));

        // 2. Actualización omitiendo apiKey -> preserva
        ObjectNode updateNoKey = registered.deepCopy();
        updateNoKey.put("name", "Lifecycle Route Renamed");
        updateNoKey.remove("apiKey");
        ObjectNode updated1 = state.put(id, updateNoKey);
        Assertions.assertTrue(updated1.path("hasApiKey").asBoolean(), "Omitir apiKey debe conservar el secreto");
        Assertions.assertTrue(storage.hasSecretSync(owner));

        // 3. Actualización con apiKey="" -> borra explícitamente
        ObjectNode updateClearKey = registered.deepCopy();
        updateClearKey.put("apiKey", "");
        ObjectNode updated2 = state.put(id, updateClearKey);
        Assertions.assertFalse(updated2.path("hasApiKey").asBoolean(), "apiKey=\"\" debe eliminar el secreto");
        Assertions.assertFalse(storage.hasSecretSync(owner));

        // 4. Volver a asignar clave y luego borrar la ruta completa
        ObjectNode reassignKey = registered.deepCopy();
        reassignKey.put("apiKey", "new-secret-before-delete");
        state.put(id, reassignKey);
        Assertions.assertTrue(storage.hasSecretSync(owner));

        // Eliminar ruta
        state.delete(id);
        Assertions.assertFalse(storage.hasSecretSync(owner),
                "Eliminar la ruta debe eliminar su secreto asociado de SQLite");
        Assertions.assertThrows(NotFoundException.class, () -> state.route(id));
    }

    @Test
    public void testInvalidApiKeyAndBotTokenRejectionAndNoMutation() throws Exception {
        StorageService storage = createStorage(VALID_KEY_1);
        storage.init();

        GatewayState state = new GatewayState();
        state.json = json;
        state.storage = storage;
        state.config = storage.config;
        state.hostMetrics = new HostMetricsService();

        // 1. Registrar ruta inicial con apiKey válido
        ObjectNode r = createBaseRoute("Validation Test Route", "valr", "initial-valid-key-123");
        ObjectNode registered = state.put(null, r);
        String id = registered.path("id").asText();
        String owner = "backend:" + id;

        Assertions.assertTrue(registered.path("hasApiKey").asBoolean());
        Assertions.assertTrue(storage.hasSecretSync(owner));

        // 2. Probar rechazos estrictos de apiKey inválido y verificar NO MUTACIÓN
        List<ObjectNode> invalidKeyPayloads = new ArrayList<>();

        // Null
        ObjectNode pNull = registered.deepCopy();
        pNull.putNull("apiKey");
        invalidKeyPayloads.add(pNull);

        // Numérico
        ObjectNode pNum = registered.deepCopy();
        pNum.put("apiKey", 123456);
        invalidKeyPayloads.add(pNum);

        // Booleano
        ObjectNode pBool = registered.deepCopy();
        pBool.put("apiKey", true);
        invalidKeyPayloads.add(pBool);

        // Solo espacios
        ObjectNode pSpaces = registered.deepCopy();
        pSpaces.put("apiKey", "    ");
        invalidKeyPayloads.add(pSpaces);

        // Controles y caracteres no permitidos en cabeceras HTTP (CR, LF, TAB, NUL, DEL, no-ASCII)
        for (String badCharKey : List.of("key\nwith\nnewline", "key\rwith\rcr", "key\twith\ttab",
                "key\u0000with\u0000nul", "key\u007fwith\u007fdel", "key-con-\u00f1-no-ascii")) {
            ObjectNode pCtrl = registered.deepCopy();
            pCtrl.put("apiKey", badCharKey);
            invalidKeyPayloads.add(pCtrl);
        }

        // Longitud excesiva (> 4096)
        ObjectNode pTooLong = registered.deepCopy();
        pTooLong.put("apiKey", "k".repeat(4097));
        invalidKeyPayloads.add(pTooLong);

        // apiKeyEnv no vacío (obsoleto, prohibido)
        ObjectNode pEnv = registered.deepCopy();
        pEnv.put("apiKeyEnv", "MY_FORBIDDEN_ENV_VAR");
        invalidKeyPayloads.add(pEnv);

        for (ObjectNode invalidPayload : invalidKeyPayloads) {
            invalidPayload.put("name", "Attempted Corrupted Name");
            Assertions.assertThrows(BadRequestException.class, () -> state.put(id, invalidPayload),
                    "Debe rechazar con BadRequestException (HTTP 400): " + invalidPayload.toString());

            // VERIFICAR NO MUTACIÓN: la ruta debe conservar su nombre previo y hasApiKey=true
            ObjectNode currentRoute = state.route(id);
            Assertions.assertEquals("Validation Test Route", currentRoute.path("name").asText(),
                    "El nombre no debe haber mutado tras el rechazo 400");
            Assertions.assertTrue(currentRoute.path("hasApiKey").asBoolean(),
                    "hasApiKey debe seguir siendo true tras el rechazo 400");
            Assertions.assertTrue(storage.hasSecretSync(owner),
                    "El secreto en DB debe permanecer intacto tras el rechazo 400");
        }

        // 3. Registrar settings inicial con botToken válido
        ObjectNode tgValidSettings = json.createObjectNode();
        ObjectNode tgValid = json.createObjectNode();
        tgValid.put("enabled", true);
        tgValid.put("chatId", "-100112233");
        tgValid.put("botToken", "valid_token-123:ABC_xyz");
        tgValidSettings.set("telegram", tgValid);

        ObjectNode settingsRes = state.updateSettings(tgValidSettings);
        Assertions.assertTrue(settingsRes.path("telegram").path("tokenConfigured").asBoolean());
        Assertions.assertTrue(storage.hasSecretSync("telegram:bot-token"));

        // 4. Probar rechazos estrictos de botToken inválido y verificar NO MUTACIÓN
        List<ObjectNode> invalidTokenPayloads = new ArrayList<>();

        // Null
        ObjectNode tNull = json.createObjectNode();
        ObjectNode tgN = json.createObjectNode();
        tgN.put("enabled", true);
        tgN.put("chatId", "-100112233");
        tgN.putNull("botToken");
        tNull.set("telegram", tgN);
        invalidTokenPayloads.add(tNull);

        // Numérico
        ObjectNode tNum = json.createObjectNode();
        ObjectNode tgNum = json.createObjectNode();
        tgNum.put("enabled", true);
        tgNum.put("chatId", "-100112233");
        tgNum.put("botToken", 987654);
        tNum.set("telegram", tgNum);
        invalidTokenPayloads.add(tNum);

        // Tokens con caracteres inválidos para segmento URL de Telegram (espacios, '/', '?', '#', '%', controles)
        for (String badToken : List.of("token con espacios", "token/con/slash", "token?con?query",
                "token#con#hash", "token%20con%20pct", "token\nline", "token\ttab", "t".repeat(4097))) {
            ObjectNode tBad = json.createObjectNode();
            ObjectNode tgB = json.createObjectNode();
            tgB.put("enabled", true);
            tgB.put("chatId", "-100112233");
            tgB.put("botToken", badToken);
            tBad.set("telegram", tgB);
            invalidTokenPayloads.add(tBad);
        }

        // Intentar eliminar token con enabled=true -> prohibido
        ObjectNode tDelEnabled = json.createObjectNode();
        ObjectNode tgDel = json.createObjectNode();
        tgDel.put("enabled", true);
        tgDel.put("chatId", "-100112233");
        tgDel.put("botToken", "");
        tDelEnabled.set("telegram", tgDel);
        invalidTokenPayloads.add(tDelEnabled);

        for (ObjectNode invalidSetting : invalidTokenPayloads) {
            Assertions.assertThrows(BadRequestException.class, () -> state.updateSettings(invalidSetting),
                    "Debe rechazar con BadRequestException (HTTP 400): " + invalidSetting.toString());

            // VERIFICAR NO MUTACIÓN: settings debe mantener tokenConfigured=true y secreto en DB
            ObjectNode currentSettings = state.settings();
            Assertions.assertTrue(currentSettings.path("telegram").path("tokenConfigured").asBoolean(),
                    "tokenConfigured debe permanecer en true tras el rechazo");
            Assertions.assertTrue(storage.hasSecretSync("telegram:bot-token"),
                    "El botToken en DB debe permanecer intacto tras el rechazo 400");
        }
    }

    @Test
    public void testAtomicCoherentRouteAndKeyCaptureDuringLiveRotation() throws Exception {
        StorageService storage = createStorage(VALID_KEY_1);
        storage.init();

        GatewayState state = new GatewayState();
        state.json = json;
        state.storage = storage;
        state.config = storage.config;
        state.hostMetrics = new HostMetricsService();

        String urlA = "http://127.0.0.1:19999/auth-alpha";
        String keyA = "key-alpha-111";
        String urlB = "http://127.0.0.1:19999/auth-beta";
        String keyB = "key-beta-222";

        ObjectNode r = createBaseRoute("Coherent Rotation Route", "coh", keyA);
        r.put("authUrl", urlA);
        ObjectNode created = state.put(null, r);
        String id = created.path("id").asText();

        // Ejecutar rotaciones y capturas concurrentes
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger inconsistencies = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);

        // Hilo lector continuo (simula peticiones entrantes de auth/acl usando matchRoute)
        Thread reader = new Thread(() -> {
            try {
                while (running.get()) {
                    GatewayState.RouteSnapshot snapshot = state.matchRoute("coh_client", true);
                    if (snapshot != null) {
                        String capturedUrl = snapshot.route().path("authUrl").asText();
                        String capturedKey = snapshot.apiKey();
                        // Debe ser estrictamente (urlA, keyA) O (urlB, keyB), jamás cruzado
                        boolean isPairA = urlA.equals(capturedUrl) && keyA.equals(capturedKey);
                        boolean isPairB = urlB.equals(capturedUrl) && keyB.equals(capturedKey);
                        if (!isPairA && !isPairB) {
                            inconsistencies.incrementAndGet();
                        }
                    }
                    Thread.yield();
                }
            } finally {
                latch.countDown();
            }
        });

        // Hilo escritor/rotador
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 50; i++) {
                    String targetUrl = (i % 2 == 0) ? urlB : urlA;
                    String targetKey = (i % 2 == 0) ? keyB : keyA;
                    ObjectNode update = created.deepCopy();
                    update.put("authUrl", targetUrl);
                    update.put("apiKey", targetKey);
                    state.put(id, update);
                    Thread.yield();
                }
            } finally {
                running.set(false);
                latch.countDown();
            }
        });

        reader.start();
        writer.start();
        Assertions.assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS));

        Assertions.assertEquals(0, inconsistencies.get(),
                "Durante la rotación live de ruta y clave jamás debe observarse un estado mixto o incoherente");
    }
}
