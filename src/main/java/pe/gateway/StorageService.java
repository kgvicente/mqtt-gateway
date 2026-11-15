package pe.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@ApplicationScoped
public class StorageService {
    private static final Logger LOG = Logger.getLogger(StorageService.class);

    @Inject
    GatewayConfig config;

    @Inject
    ObjectMapper json;

    private Connection connection;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sqlite-writer");
        t.setDaemon(true);
        return t;
    });

    private final Object lifecycleLock = new Object();
    private final AtomicReference<CheckpointPayload> pendingCheckpoint = new AtomicReference<>();
    private final AtomicBoolean checkpointScheduled = new AtomicBoolean(false);

    private volatile boolean closed = false;
    private volatile boolean healthy = true;
    private Path dbPath;

    public static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static String formatIso(Instant instant) {
        if (instant == null) return null;
        return ISO_FORMATTER.format(instant);
    }

    public static String formatIso(long epochMs) {
        return ISO_FORMATTER.format(Instant.ofEpochMilli(epochMs));
    }

    public record Migration(int version, String name, String script, String checksum) {}
    private record CheckpointPayload(String date, String stateJson) {}

    private volatile byte[] masterKeyBytes;
    private volatile SecureRandom secureRandom;
    private static final String CANARY_PLAINTEXT = "CANARY_OK_2026";

    private byte[] resolveMasterKey() {
        String key = config.masterKey().orElse(null);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("GATEWAY_MASTER_KEY es obligatoria: configure una clave Base64 de exactamente 32 bytes");
        }
        return decodeAndValidateMasterKey(key);
    }

    private static byte[] decodeAndValidateMasterKey(String b64) {
        try {
            byte[] key = Base64.getDecoder().decode(b64.trim());
            if (key.length != 32) {
                throw new IllegalStateException("GATEWAY_MASTER_KEY debe ser una cadena Base64 de exactamente 32 bytes (256 bits), longitud obtenida: " + key.length);
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("GATEWAY_MASTER_KEY no es un Base64 válido");
        }
    }

    public static final class EncryptedPayload {
        private final int version;
        private final byte[] nonce;
        private final byte[] ciphertext;

        public EncryptedPayload(int version, byte[] nonce, byte[] ciphertext) {
            this.version = version;
            this.nonce = nonce != null ? nonce.clone() : new byte[0];
            this.ciphertext = ciphertext != null ? ciphertext.clone() : new byte[0];
        }

        public EncryptedPayload(byte[] nonce, byte[] ciphertext) {
            this(1, nonce, ciphertext);
        }

        public int version() { return version; }
        public byte[] nonce() { return nonce.clone(); }
        public byte[] ciphertext() { return ciphertext.clone(); }
    }

    public EncryptedPayload encryptSecret(String owner, String plaintext) throws GeneralSecurityException {
        if (this.masterKeyBytes == null) {
            throw new IllegalStateException("Clave maestra no inicializada");
        }
        if (this.secureRandom == null) {
            this.secureRandom = new SecureRandom();
        }
        if (plaintext == null) plaintext = "";
        byte[] nonce = new byte[12];
        this.secureRandom.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(this.masterKeyBytes, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

        byte[] aad = ("v1:" + owner).getBytes(StandardCharsets.UTF_8);
        cipher.updateAAD(aad);

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return new EncryptedPayload(1, nonce, ciphertext);
    }

    public String decryptSecret(int version, String owner, byte[] nonce, byte[] ciphertext) throws GeneralSecurityException {
        if (this.masterKeyBytes == null) {
            throw new IllegalStateException("Clave maestra no inicializada");
        }
        if (version != 1) {
            throw new GeneralSecurityException("Versión de cifrado no compatible: " + version);
        }
        if (nonce == null || nonce.length != 12) {
            throw new GeneralSecurityException("Longitud de nonce inválida: " + (nonce == null ? 0 : nonce.length));
        }
        if (ciphertext == null || ciphertext.length < 16) {
            throw new GeneralSecurityException("Longitud de ciphertext inválida: " + (ciphertext == null ? 0 : ciphertext.length));
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(this.masterKeyBytes, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        byte[] aad = ("v1:" + owner).getBytes(StandardCharsets.UTF_8);
        cipher.updateAAD(aad);

        byte[] plaintextBytes = cipher.doFinal(ciphertext);
        return new String(plaintextBytes, StandardCharsets.UTF_8);
    }

    public String decryptSecret(String owner, byte[] nonce, byte[] ciphertext) throws GeneralSecurityException {
        return decryptSecret(1, owner, nonce, ciphertext);
    }

    private void initSecretsAndMigrate() throws Exception {
        // Validar SIEMPRE TODOS los blobs existentes en managed_secrets antes de cualquier mutación
        boolean canaryExists = false;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT owner, version, nonce, ciphertext FROM managed_secrets")) {
            while (rs.next()) {
                String owner = rs.getString("owner");
                int ver = rs.getInt("version");
                byte[] nonce = rs.getBytes("nonce");
                byte[] ciphertext = rs.getBytes("ciphertext");
                try {
                    String decrypted = decryptSecret(ver, owner, nonce, ciphertext);
                    if ("canary".equals(owner)) {
                        canaryExists = true;
                        if (!CANARY_PLAINTEXT.equals(decrypted)) {
                            throw new IllegalStateException("Validación de canary falló: clave maestra incorrecta");
                        }
                    }
                } catch (GeneralSecurityException e) {
                    if ("canary".equals(owner)) {
                        throw new IllegalStateException("La clave maestra proporcionada no puede descifrar los datos existentes (canary validation failed)");
                    } else {
                        throw new IllegalStateException("Integridad comprometida o clave incorrecta al descifrar secreto '" + owner + "'", e);
                    }
                }
            }
        }

        // Verificar si la migración de variables de entorno ya fue realizada
        boolean envMigrated = false;
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM settings WHERE key = 'env_secrets_migrated_at'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    envMigrated = true;
                }
            }
        }

        // Si ya hay canary y ya está migrado, no hay nada que mutar
        if (canaryExists && envMigrated) {
            return;
        }

        // Preparar canary si no existe
        EncryptedPayload newCanaryPayload = null;
        if (!canaryExists) {
            newCanaryPayload = encryptSecret("canary", CANARY_PLAINTEXT);
        }

        // Preparar migración de secretos si no ha sido realizada
        Map<String, EncryptedPayload> secretsToInsert = new LinkedHashMap<>();
        List<String> routeIdsToClear = new ArrayList<>();
        if (!envMigrated) {
            Map<String, String> resolvedSecrets = new LinkedHashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, api_key_env FROM routes WHERE api_key_env IS NOT NULL AND api_key_env != ''")) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String envVar = rs.getString("api_key_env");
                    String secret = System.getenv(envVar);
                    if (secret == null || secret.isBlank()) {
                        throw new IllegalStateException("Fallo en migración de secretos: la variable de entorno '" + envVar + "' referenciada por la ruta '" + id + "' no está definida o está vacía");
                    }
                    GatewayState.validateHttpHeaderValue("apiKey", secret);
                    resolvedSecrets.put("backend:" + id, secret);
                    routeIdsToClear.add(id);
                }
            }

            String telegramToken = config.telegram().flatMap(GatewayConfig.Telegram::botToken).orElse(null);
            if (telegramToken != null && !telegramToken.isBlank()) {
                GatewayState.validateTelegramBotToken(telegramToken);
                resolvedSecrets.put("telegram:bot-token", telegramToken);
            }

            for (Map.Entry<String, String> entry : resolvedSecrets.entrySet()) {
                secretsToInsert.put(entry.getKey(), encryptSecret(entry.getKey(), entry.getValue()));
            }
        }

        // Transacción única atómica: canary + secrets + clearrefs + marker
        boolean auto = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            String now = formatIso(Instant.now());

            if (newCanaryPayload != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO managed_secrets (owner, version, nonce, ciphertext, created_at, updated_at) VALUES ('canary', ?, ?, ?, ?, ?)")) {
                    ps.setInt(1, newCanaryPayload.version());
                    ps.setBytes(2, newCanaryPayload.nonce());
                    ps.setBytes(3, newCanaryPayload.ciphertext());
                    ps.setString(4, now);
                    ps.setString(5, now);
                    ps.executeUpdate();
                }
            }

            if (!secretsToInsert.isEmpty()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO managed_secrets (owner, version, nonce, ciphertext, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<String, EncryptedPayload> entry : secretsToInsert.entrySet()) {
                        ps.setString(1, entry.getKey());
                        ps.setInt(2, entry.getValue().version());
                        ps.setBytes(3, entry.getValue().nonce());
                        ps.setBytes(4, entry.getValue().ciphertext());
                        ps.setString(5, now);
                        ps.setString(6, now);
                        ps.executeUpdate();
                    }
                }
            }

            if (!routeIdsToClear.isEmpty()) {
                try (PreparedStatement ps = connection.prepareStatement("UPDATE routes SET api_key_env = NULL WHERE id = ?")) {
                    for (String rId : routeIdsToClear) {
                        ps.setString(1, rId);
                        ps.executeUpdate();
                    }
                }
            }

            if (!envMigrated) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES ('env_secrets_migrated_at', ?, ?)")) {
                    ps.setString(1, now);
                    ps.setString(2, now);
                    ps.executeUpdate();
                }
            }

            connection.commit();
            LOG.info("Inicialización de secretos y migración atómica completada exitosamente");
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    private Map<String, String> loadAllSecretsInternal() throws Exception {
        Map<String, String> map = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT owner, version, nonce, ciphertext FROM managed_secrets WHERE owner != 'canary'")) {
            while (rs.next()) {
                String owner = rs.getString("owner");
                int ver = rs.getInt("version");
                byte[] nonce = rs.getBytes("nonce");
                byte[] ciphertext = rs.getBytes("ciphertext");
                try {
                    String decrypted = decryptSecret(ver, owner, nonce, ciphertext);
                    map.put(owner, decrypted);
                } catch (GeneralSecurityException e) {
                    throw new IllegalStateException("Integridad comprometida o clave incorrecta al descifrar secreto '" + owner + "'", e);
                }
            }
        }
        return map;
    }

    public Map<String, String> loadAllSecretsSync() throws Exception {
        return executeSync(this::loadAllSecretsInternal);
    }

    public String getSecretSync(String owner) throws Exception {
        return executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT version, nonce, ciphertext FROM managed_secrets WHERE owner = ?")) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int ver = rs.getInt("version");
                        byte[] nonce = rs.getBytes("nonce");
                        byte[] ciphertext = rs.getBytes("ciphertext");
                        return decryptSecret(ver, owner, nonce, ciphertext);
                    }
                }
            }
            return null;
        });
    }

    public void saveSecretSync(String owner, String plaintext) throws Exception {
        executeSync(() -> {
            EncryptedPayload ep = encryptSecret(owner, plaintext);
            String now = formatIso(Instant.now());
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO managed_secrets (owner, version, nonce, ciphertext, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, owner);
                ps.setInt(2, ep.version());
                ps.setBytes(3, ep.nonce());
                ps.setBytes(4, ep.ciphertext());
                ps.setString(5, now);
                ps.setString(6, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteSecretSync(String owner) throws Exception {
        executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM managed_secrets WHERE owner = ?")) {
                ps.setString(1, owner);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public boolean hasSecretSync(String owner) throws Exception {
        return executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM managed_secrets WHERE owner = ? LIMIT 1")) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void init() throws Exception {
        executeSync(() -> {
            // Validación estricta de la clave maestra antes de abrir SQLite o aplicar migraciones
            this.masterKeyBytes = resolveMasterKey();
            this.secureRandom = new SecureRandom();

            Path dir = Path.of(config.dataDir());
            Files.createDirectories(dir);
            setRestrictivePermissions(dir, true);

            dbPath = dir.resolve("gateway.db");
            String url = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();
            connection = new org.sqlite.JDBC().connect(url, new Properties());
            if (connection == null) {
                throw new SQLException("Driver org.sqlite.JDBC no aceptó la URL: " + url);
            }
            setRestrictivePermissions(dbPath, false);

            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA foreign_keys=ON;");
                st.execute("PRAGMA busy_timeout=5000;");
                st.execute("PRAGMA synchronous=FULL;");
            }

            runMigrations();
            initSecretsAndMigrate();
            setRestrictivePermissions(dbPath, false);
            return null;
        });
    }

    public static void setRestrictivePermissions(Path path, boolean dir) {
        try {
            if (Files.exists(path)) {
                String perms = dir ? "rwx------" : "rw-------";
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
            }
        } catch (UnsupportedOperationException ignored) {
            // Entorno sin soporte POSIX (ej. Windows)
        } catch (IOException e) {
            throw new IllegalStateException("Fallo al establecer permisos POSIX restrictivos en " + path.getFileName() + ": " + e.getClass().getSimpleName(), e);
        }
    }

    private void runMigrations() throws Exception {
        List<Migration> knownMigrations = new ArrayList<>();
        List<String> migrationFiles = List.of("V1__initial_schema.sql", "V2__monitoring.sql", "V3__telegram_outbox.sql", "V4__managed_secrets.sql");
        for (int i = 0; i < migrationFiles.size(); i++) {
            int version = i + 1;
            String migrationName = migrationFiles.get(i);
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("db/migrations/" + migrationName)) {
                if (in == null) {
                    throw new IllegalStateException("No se encontró script de migración: " + migrationName);
                }
                String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String normalized = script.replace("\r\n", "\n").trim();
                String checksum = computeSha256(normalized);
                knownMigrations.add(new Migration(version, migrationName, normalized, checksum));
            }
        }

        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_migrations (" +
                    "version INTEGER PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "checksum TEXT NOT NULL, " +
                    "applied_at TEXT NOT NULL" +
                    ");");
        }

        Map<Integer, String> applied = new LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, checksum FROM schema_migrations ORDER BY version ASC")) {
            while (rs.next()) {
                applied.put(rs.getInt("version"), rs.getString("checksum"));
            }
        }

        for (Map.Entry<Integer, String> entry : applied.entrySet()) {
            int ver = entry.getKey();
            String appliedChecksum = entry.getValue();
            Migration known = knownMigrations.stream().filter(m -> m.version() == ver).findFirst().orElse(null);
            if (known == null) {
                throw new IllegalStateException("Versión de migración desconocida en la base de datos: " + ver);
            }
            if (!known.checksum().equals(appliedChecksum)) {
                throw new IllegalStateException("Checksum inválido para migración versión " + ver + " (" + known.name() + ")");
            }
        }

        for (Migration m : knownMigrations) {
            if (!applied.containsKey(m.version())) {
                boolean autoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(false);
                    try (Statement st = connection.createStatement()) {
                        for (String sql : m.script().split(";")) {
                            String trimmed = sql.trim();
                            if (!trimmed.isEmpty()) {
                                st.execute(trimmed);
                            }
                        }
                    }
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO schema_migrations (version, name, checksum, applied_at) VALUES (?, ?, ?, ?)")) {
                        ps.setInt(1, m.version());
                        ps.setString(2, m.name());
                        ps.setString(3, m.checksum());
                        ps.setString(4, Instant.now().toString());
                        ps.executeUpdate();
                    }
                    connection.commit();
                    LOG.infof("Migración %s (v%d) aplicada con éxito", m.name(), m.version());
                } catch (Exception e) {
                    connection.rollback();
                    healthy = false;
                    throw new IllegalStateException("Fallo al aplicar migración " + m.name() + ": " + e.getMessage(), e);
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            }
        }
    }

    private static String computeSha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            String h = Integer.toHexString(0xff & b);
            if (h.length() == 1) hex.append('0');
            hex.append(h);
        }
        return hex.toString();
    }

    public boolean isHealthy() {
        return healthy && !closed;
    }

    public boolean isImportedOrSeeded() throws Exception {
        return executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM settings WHERE key IN ('imported_from_json_at', 'seeded_from_yaml_at') LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        });
    }

    private static void bindRoute(PreparedStatement ps, ObjectNode route) throws SQLException {
        ps.setString(1, route.path("id").asText());
        ps.setString(2, route.path("name").asText());
        ps.setInt(3, route.path("enabled").asBoolean() ? 1 : 0);
        ps.setString(4, route.path("authUrl").asText());
        ps.setString(5, route.path("aclUrl").asText());
        ps.setString(6, route.path("healthUrl").asText());
        String env = route.hasNonNull("apiKeyEnv") ? route.path("apiKeyEnv").asText() : null;
        ps.setString(7, env != null && !env.isBlank() ? env : null);
        ps.setString(8, route.path("authPrefixes").toString());
        ps.setString(9, route.path("aclPrefixes").toString());
        ps.setString(10, route.path("createdAt").asText());
        if (route.hasNonNull("monitoring")) {
            ps.setString(11, route.get("monitoring").toString());
        } else {
            ps.setString(11, null);
        }
        if (route.hasNonNull("telegram")) {
            ps.setString(12, route.get("telegram").toString());
        } else {
            ps.setString(12, null);
        }
    }

    private static void bindIncident(PreparedStatement ps, ObjectNode incident) throws SQLException {
        ps.setString(1, incident.path("id").asText());
        ps.setString(2, incident.path("backendId").asText());
        ps.setString(3, incident.path("backend").asText());
        ps.setString(4, incident.path("startedAt").asText());
        ps.setString(5, incident.hasNonNull("endedAt") ? incident.path("endedAt").asText() : null);
        if (incident.hasNonNull("durationMs")) ps.setLong(6, incident.path("durationMs").asLong());
        else ps.setNull(6, Types.BIGINT);
        ps.setString(7, incident.hasNonNull("reason") ? incident.path("reason").asText() : null);
        ps.setString(8, incident.hasNonNull("endReason") ? incident.path("endReason").asText() : null);
    }

    public List<ObjectNode> loadRoutes() throws Exception {
        return executeSync(() -> {
            List<ObjectNode> list = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM routes ORDER BY created_at ASC")) {
                while (rs.next()) {
                    ObjectNode node = json.createObjectNode();
                    node.put("id", rs.getString("id"));
                    node.put("name", rs.getString("name"));
                    node.put("enabled", rs.getInt("enabled") == 1);
                    node.put("authUrl", rs.getString("auth_url"));
                    node.put("aclUrl", rs.getString("acl_url"));
                    node.put("healthUrl", rs.getString("health_url"));
                    String apiKeyEnv = rs.getString("api_key_env");
                    if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
                        node.put("apiKeyEnv", apiKeyEnv);
                    } else {
                        node.putNull("apiKeyEnv");
                    }
                    node.set("authPrefixes", json.readTree(rs.getString("auth_prefixes")));
                    node.set("aclPrefixes", json.readTree(rs.getString("acl_prefixes")));
                    node.put("createdAt", rs.getString("created_at"));
                    String monitoringJson = null;
                    try {
                        monitoringJson = rs.getString("monitoring_json");
                    } catch (SQLException ignored) {}
                    if (monitoringJson != null && !monitoringJson.isBlank()) {
                        node.set("monitoring", json.readTree(monitoringJson));
                    } else {
                        node.putNull("monitoring");
                    }
                    String telegramJson = null;
                    try {
                        telegramJson = rs.getString("telegram_json");
                    } catch (SQLException ignored) {}
                    if (telegramJson != null && !telegramJson.isBlank()) {
                        node.set("telegram", json.readTree(telegramJson));
                    } else {
                        node.putNull("telegram");
                    }
                    list.add(node);
                }
            }
            return list;
        });
    }

    public void saveRouteSync(ObjectNode route) throws Exception {
        executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO routes (id, name, enabled, auth_url, acl_url, health_url, api_key_env, auth_prefixes, acl_prefixes, created_at, monitoring_json, telegram_json) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindRoute(ps, route);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteRouteSync(String id) throws Exception {
        executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM routes WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<ObjectNode> loadIncidents() throws Exception {
        return executeSync(() -> {
            List<ObjectNode> list = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM incidents ORDER BY started_at ASC")) {
                while (rs.next()) {
                    ObjectNode node = json.createObjectNode();
                    node.put("id", rs.getString("id"));
                    node.put("backendId", rs.getString("backend_id"));
                    node.put("backend", rs.getString("backend_name"));
                    node.put("startedAt", rs.getString("started_at"));
                    String endedAt = rs.getString("ended_at");
                    if (endedAt != null) node.put("endedAt", endedAt); else node.putNull("endedAt");
                    long duration = rs.getLong("duration_ms");
                    if (!rs.wasNull()) node.put("durationMs", duration); else node.putNull("durationMs");
                    String reason = rs.getString("reason");
                    if (reason != null) node.put("reason", reason); else node.putNull("reason");
                    String endReason = rs.getString("end_reason");
                    if (endReason != null) node.put("endReason", endReason); else node.putNull("endReason");
                    list.add(node);
                }
            }
            return list;
        });
    }

    public void saveIncidentAsync(ObjectNode incident) {
        final ObjectNode copy = incident.deepCopy();
        synchronized (lifecycleLock) {
            if (closed) return;
            try {
                writer.submit(() -> {
                    try {
                        try (PreparedStatement ps = connection.prepareStatement(
                                "INSERT OR REPLACE INTO incidents (id, backend_id, backend_name, started_at, ended_at, duration_ms, reason, end_reason) " +
                                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                            bindIncident(ps, copy);
                            ps.executeUpdate();
                        }
                    } catch (Exception e) {
                        healthy = false;
                        LOG.error("Fallo al persistir incidente en SQLite", e);
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // Servidor apagándose; se ignora descarte ordenado
            }
        }
    }

    public String loadCheckpoint(String date) throws Exception {
        return executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT state_json FROM checkpoints WHERE checkpoint_date = ?")) {
                ps.setString(1, date);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString("state_json");
                }
            }
            return null;
        });
    }

    public void saveCheckpointAsync(String date, String stateJson) {
        synchronized (lifecycleLock) {
            if (closed) return;
            pendingCheckpoint.set(new CheckpointPayload(date, stateJson));
            if (checkpointScheduled.compareAndSet(false, true)) {
                try {
                    writer.submit(() -> {
                        checkpointScheduled.set(false);
                        CheckpointPayload cp = pendingCheckpoint.getAndSet(null);
                        if (cp != null) {
                            try {
                                saveCheckpointInternal(cp.date(), cp.stateJson());
                            } catch (Exception e) {
                                healthy = false;
                                LOG.error("Fallo al guardar checkpoint coalescido en SQLite", e);
                            }
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                    // Servidor apagándose
                }
            }
        }
    }

    private void saveCheckpointInternal(String date, String stateJson) throws SQLException {
        boolean auto = connection.getAutoCommit();
        if (auto) connection.setAutoCommit(false);
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO checkpoints (checkpoint_date, state_json, updated_at) VALUES (?, ?, ?)")) {
                ps.setString(1, date);
                ps.setString(2, stateJson);
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM checkpoints WHERE checkpoint_date != ?")) {
                ps.setString(1, date);
                ps.executeUpdate();
            }
            if (auto) connection.commit();
        } catch (Exception e) {
            if (auto) connection.rollback();
            throw e;
        } finally {
            if (auto) connection.setAutoCommit(true);
        }
    }

    public Map<String, String> loadAllSettings() throws Exception {
        return executeSync(() -> {
            Map<String, String> map = new LinkedHashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT key, value FROM settings")) {
                while (rs.next()) {
                    map.put(rs.getString("key"), rs.getString("value"));
                }
            }
            return map;
        });
    }

    public String loadSetting(String key) throws Exception {
        return executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString("value");
                }
            }
            return null;
        });
    }

    public void saveSettingSync(String key, String value) throws Exception {
        executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES (?, ?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void importLegacyData(List<ObjectNode> routes, List<ObjectNode> incidents, String dailyStateDate, String dailyStateJson) throws Exception {
        importLegacyData(routes, incidents, dailyStateDate, dailyStateJson, Collections.emptyMap());
    }

    public void importLegacyData(List<ObjectNode> routes, List<ObjectNode> incidents, String dailyStateDate, String dailyStateJson, Map<String, String> legacyKeys) throws Exception {
        executeSync(() -> {
            boolean auto = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                String now = formatIso(Instant.now());
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO routes (id, name, enabled, auth_url, acl_url, health_url, api_key_env, auth_prefixes, acl_prefixes, created_at, monitoring_json, telegram_json) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (ObjectNode r : routes) {
                        bindRoute(ps, r);
                        ps.executeUpdate();
                    }
                }
                if (legacyKeys != null && !legacyKeys.isEmpty()) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT OR REPLACE INTO managed_secrets (owner, version, nonce, ciphertext, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                        for (Map.Entry<String, String> entry : legacyKeys.entrySet()) {
                            String owner = entry.getKey();
                            String secretVal = entry.getValue();
                            if (owner.startsWith("backend:")) {
                                GatewayState.validateHttpHeaderValue("apiKey", secretVal);
                            } else if ("telegram:bot-token".equals(owner)) {
                                GatewayState.validateTelegramBotToken(secretVal);
                            }
                            EncryptedPayload ep = encryptSecret(owner, secretVal);
                            ps.setString(1, owner);
                            ps.setInt(2, ep.version());
                            ps.setBytes(3, ep.nonce());
                            ps.setBytes(4, ep.ciphertext());
                            ps.setString(5, now);
                            ps.setString(6, now);
                            ps.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO incidents (id, backend_id, backend_name, started_at, ended_at, duration_ms, reason, end_reason) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (ObjectNode inc : incidents) {
                        bindIncident(ps, inc);
                        ps.executeUpdate();
                    }
                }
                if (dailyStateDate != null && dailyStateJson != null) {
                    saveCheckpointInternal(dailyStateDate, dailyStateJson);
                }
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO settings (key, value, updated_at) VALUES ('imported_from_json_at', ?, ?)")) {
                    ps.setString(1, now);
                    ps.setString(2, now);
                    ps.executeUpdate();
                }
                connection.commit();
                LOG.info("Importación de datos legados completada exitosamente en una única transacción");
            } catch (Exception e) {
                connection.rollback();
                healthy = false;
                throw new IllegalStateException("Error al importar datos legados a SQLite: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(auto);
            }
            return null;
        });
    }

    public void seedRoutes(List<ObjectNode> routes) throws Exception {
        seedRoutes(routes, Collections.emptyMap());
    }

    public void seedRoutes(List<ObjectNode> routes, Map<String, String> initialSecrets) throws Exception {
        executeSync(() -> {
            boolean auto = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                String now = formatIso(Instant.now());
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO routes (id, name, enabled, auth_url, acl_url, health_url, api_key_env, auth_prefixes, acl_prefixes, created_at, monitoring_json, telegram_json) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (ObjectNode r : routes) {
                        bindRoute(ps, r);
                        ps.executeUpdate();
                    }
                }
                if (initialSecrets != null && !initialSecrets.isEmpty()) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT OR REPLACE INTO managed_secrets (owner, version, nonce, ciphertext, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                        for (Map.Entry<String, String> entry : initialSecrets.entrySet()) {
                            String owner = entry.getKey();
                            String secretVal = entry.getValue();
                            if (owner.startsWith("backend:")) {
                                GatewayState.validateHttpHeaderValue("apiKey", secretVal);
                            } else if ("telegram:bot-token".equals(owner)) {
                                GatewayState.validateTelegramBotToken(secretVal);
                            }
                            EncryptedPayload ep = encryptSecret(owner, secretVal);
                            ps.setString(1, owner);
                            ps.setInt(2, ep.version());
                            ps.setBytes(3, ep.nonce());
                            ps.setBytes(4, ep.ciphertext());
                            ps.setString(5, now);
                            ps.setString(6, now);
                            ps.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES ('seeded_from_yaml_at', ?, ?)")) {
                    ps.setString(1, now);
                    ps.setString(2, now);
                    ps.executeUpdate();
                }
                connection.commit();
                LOG.info("Siembra de rutas desde YAML completada exitosamente");
            } catch (Exception e) {
                connection.rollback();
                healthy = false;
                throw new IllegalStateException("Error al sembrar rutas iniciales: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(auto);
            }
            return null;
        });
    }

    public void saveSettingsAtomicSync(Map<String, String> settingsMap, boolean cancelAllPending,
                                      String newBotTokenToPersist, boolean shouldDeleteBotToken) throws Exception {
        executeSync(() -> {
            boolean auto = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                String now = formatIso(Instant.now());
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES (?, ?, ?)")) {
                    for (Map.Entry<String, String> entry : settingsMap.entrySet()) {
                        ps.setString(1, entry.getKey());
                        ps.setString(2, entry.getValue());
                        ps.setString(3, now);
                        ps.executeUpdate();
                    }
                }
                if (shouldDeleteBotToken) {
                    try (PreparedStatement ps = connection.prepareStatement("DELETE FROM managed_secrets WHERE owner = 'telegram:bot-token'")) {
                        ps.executeUpdate();
                    }
                } else if (newBotTokenToPersist != null) {
                    EncryptedPayload ep = encryptSecret("telegram:bot-token", newBotTokenToPersist);
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT OR REPLACE INTO managed_secrets (owner, version, nonce, ciphertext, created_at, updated_at) VALUES ('telegram:bot-token', ?, ?, ?, ?, ?)")) {
                        ps.setInt(1, ep.version());
                        ps.setBytes(2, ep.nonce());
                        ps.setBytes(3, ep.ciphertext());
                        ps.setString(4, now);
                        ps.setString(5, now);
                        ps.executeUpdate();
                    }
                }
                if (cancelAllPending) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE notification_outbox SET status = 'cancelled' WHERE status IN ('pending', 'sending')")) {
                        ps.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                healthy = false;
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
            return null;
        });
    }

    public void saveSettingsAtomicSync(Map<String, String> settingsMap, boolean cancelAllPending) throws Exception {
        saveSettingsAtomicSync(settingsMap, cancelAllPending, null, false);
    }

    public void saveRouteAtomicSync(ObjectNode route, ObjectNode closedIncidentOrNull, boolean cancelPendingForBackend,
                                   String newApiKeyToPersist, boolean shouldDeleteApiKey) throws Exception {
        executeSync(() -> {
            boolean auto = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO routes (id, name, enabled, auth_url, acl_url, health_url, api_key_env, auth_prefixes, acl_prefixes, created_at, monitoring_json, telegram_json) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    bindRoute(ps, route);
                    ps.executeUpdate();
                }
                String backendOwner = "backend:" + route.path("id").asText();
                if (shouldDeleteApiKey) {
                    try (PreparedStatement ps = connection.prepareStatement("DELETE FROM managed_secrets WHERE owner = ?")) {
                        ps.setString(1, backendOwner);
                        ps.executeUpdate();
                    }
                } else if (newApiKeyToPersist != null) {
                    EncryptedPayload ep = encryptSecret(backendOwner, newApiKeyToPersist);
                    String now = formatIso(Instant.now());
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT OR REPLACE INTO managed_secrets (owner, version, nonce, ciphertext, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                        ps.setString(1, backendOwner);
                        ps.setInt(2, ep.version());
                        ps.setBytes(3, ep.nonce());
                        ps.setBytes(4, ep.ciphertext());
                        ps.setString(5, now);
                        ps.setString(6, now);
                        ps.executeUpdate();
                    }
                }
                if (closedIncidentOrNull != null) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT OR REPLACE INTO incidents (id, backend_id, backend_name, started_at, ended_at, duration_ms, reason, end_reason) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        bindIncident(ps, closedIncidentOrNull);
                        ps.executeUpdate();
                    }
                }
                if (cancelPendingForBackend) {
                    String backendId = route.path("id").asText();
                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE notification_outbox SET status = 'cancelled' WHERE backend_id = ? AND status IN ('pending', 'sending')")) {
                        ps.setString(1, backendId);
                        ps.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                healthy = false;
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
            return null;
        });
    }

    public void saveRouteAtomicSync(ObjectNode route, ObjectNode closedIncidentOrNull, boolean cancelPendingForBackend) throws Exception {
        saveRouteAtomicSync(route, closedIncidentOrNull, cancelPendingForBackend, null, false);
    }

    public void deleteRouteAtomicSync(String id, ObjectNode closedIncidentOrNull) throws Exception {
        executeSync(() -> {
            boolean auto = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM routes WHERE id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM managed_secrets WHERE owner = ?")) {
                    ps.setString(1, "backend:" + id);
                    ps.executeUpdate();
                }
                if (closedIncidentOrNull != null) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT OR REPLACE INTO incidents (id, backend_id, backend_name, started_at, ended_at, duration_ms, reason, end_reason) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        bindIncident(ps, closedIncidentOrNull);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE notification_outbox SET status = 'cancelled' WHERE backend_id = ? AND status IN ('pending', 'sending')")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                healthy = false;
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
            return null;
        });
    }

    public void saveIncidentWithOutboxSync(ObjectNode incident, ObjectNode outboxItemOrNull) throws Exception {
        executeSync(() -> {
            boolean auto = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO incidents (id, backend_id, backend_name, started_at, ended_at, duration_ms, reason, end_reason) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    bindIncident(ps, incident);
                    ps.executeUpdate();
                }
                if (outboxItemOrNull != null) {
                    int pendingCount = 0;
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT COUNT(*) FROM notification_outbox WHERE status IN ('pending', 'sending')")) {
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) pendingCount = rs.getInt(1);
                        }
                    }
                    String finalStatus = (pendingCount >= 10000) ? "not_enqueued" : outboxItemOrNull.path("status").asText("pending");
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT OR IGNORE INTO notification_outbox (id, backend_id, incident_id, type, chat_id, message, status, attempts, created_at, next_attempt_at, last_attempt_at, sent_at, last_error) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        bindOutboxItem(ps, outboxItemOrNull, finalStatus);
                        ps.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                healthy = false;
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
            return null;
        });
    }

    public String saveOutboxItemSync(ObjectNode item) throws Exception {
        return executeSync(() -> {
            int pendingCount = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM notification_outbox WHERE status IN ('pending', 'sending')")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) pendingCount = rs.getInt(1);
                }
            }
            String finalStatus = (pendingCount >= 10000) ? "not_enqueued" : item.path("status").asText("pending");
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO notification_outbox (id, backend_id, incident_id, type, chat_id, message, status, attempts, created_at, next_attempt_at, last_attempt_at, sent_at, last_error) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindOutboxItem(ps, item, finalStatus);
                ps.executeUpdate();
            }
            return finalStatus;
        });
    }

    private static void bindOutboxItem(PreparedStatement ps, ObjectNode item, String status) throws SQLException {
        ps.setString(1, item.path("id").asText());
        ps.setString(2, item.hasNonNull("backendId") ? item.path("backendId").asText() : null);
        ps.setString(3, item.hasNonNull("incidentId") ? item.path("incidentId").asText() : null);
        ps.setString(4, item.path("type").asText());
        ps.setString(5, item.path("chatId").asText());
        ps.setString(6, item.path("message").asText());
        ps.setString(7, status);
        ps.setInt(8, item.path("attempts").asInt(0));
        ps.setString(9, item.path("createdAt").asText());
        ps.setString(10, item.path("nextAttemptAt").asText());
        ps.setString(11, item.hasNonNull("lastAttemptAt") ? item.path("lastAttemptAt").asText() : null);
        ps.setString(12, item.hasNonNull("sentAt") ? item.path("sentAt").asText() : null);
        ps.setString(13, item.hasNonNull("lastError") ? item.path("lastError").asText() : null);
    }

    public List<ObjectNode> loadPendingOutboxBatchSync(String nowIso, int limit) throws Exception {
        return executeSync(() -> {
            List<ObjectNode> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT o.* FROM notification_outbox o " +
                    "WHERE o.status = 'pending' AND o.next_attempt_at <= ? " +
                    "  AND NOT EXISTS ( " +
                    "    SELECT 1 FROM notification_outbox prev " +
                    "    WHERE prev.chat_id = o.chat_id " +
                    "      AND prev.sequence < o.sequence " +
                    "      AND prev.status IN ('pending', 'sending') " +
                    "  ) " +
                    "ORDER BY o.sequence ASC LIMIT ?")) {
                ps.setString(1, nowIso);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapOutboxItemForWorker(rs));
                    }
                }
            }
            return list;
        });
    }

    public boolean claimOutboxSendingSync(String id, String nowIso) throws Exception {
        return executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE notification_outbox SET status = 'sending', attempts = attempts + 1, last_attempt_at = ? WHERE id = ? AND status = 'pending'")) {
                ps.setString(1, nowIso);
                ps.setString(2, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean completeOutboxSync(String id, String status, int attempts, String nextAttemptAt, String sentAt, String lastError) throws Exception {
        return executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE notification_outbox SET status = ?, attempts = ?, next_attempt_at = COALESCE(?, next_attempt_at), sent_at = ?, last_error = ? WHERE id = ? AND status = 'sending'")) {
                ps.setString(1, status);
                ps.setInt(2, attempts);
                ps.setString(3, nextAttemptAt);
                ps.setString(4, sentAt);
                ps.setString(5, lastError);
                ps.setString(6, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean completeOutboxSync(String id, String status, String nextAttemptAt, String sentAt, String lastError) throws Exception {
        return executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE notification_outbox SET status = ?, next_attempt_at = COALESCE(?, next_attempt_at), sent_at = ?, last_error = ? WHERE id = ? AND status = 'sending'")) {
                ps.setString(1, status);
                ps.setString(2, nextAttemptAt);
                ps.setString(3, sentAt);
                ps.setString(4, lastError);
                ps.setString(5, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public void updateChatActivitySync(String chatId, String lastAttemptIso) throws Exception {
        executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO telegram_chat_activity (chat_id, last_attempt_at) VALUES (?, ?)")) {
                ps.setString(1, chatId);
                ps.setString(2, lastAttemptIso);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Map<String, String> loadAllChatActivitySync() throws Exception {
        return executeSync(() -> {
            Map<String, String> map = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT chat_id, last_attempt_at FROM telegram_chat_activity")) {
                while (rs.next()) {
                    map.put(rs.getString("chat_id"), rs.getString("last_attempt_at"));
                }
            }
            return map;
        });
    }

    public void recoverOrphanedSendingSync(String nowIso) throws Exception {
        recoverOrphanedSendingSync();
    }

    public void recoverOrphanedSendingSync() throws Exception {
        executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE notification_outbox " +
                    "SET status = CASE WHEN attempts >= 5 THEN 'failed' ELSE 'pending' END, " +
                    "    last_error = CASE WHEN attempts >= 5 THEN 'attempts_exhausted' ELSE last_error END " +
                    "WHERE status = 'sending'")) {
                ps.executeUpdate();
            }
            return null;
        });
    }

    public int expirePendingOutboxBatchSync(String cutoffIso) throws Exception {
        return executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE notification_outbox SET status = 'expired', last_error = 'ttl_expired' WHERE status = 'pending' AND created_at < ?")) {
                ps.setString(1, cutoffIso);
                return ps.executeUpdate();
            }
        });
    }

    public void purgeTerminalOutboxSync(String cutoffIso) throws Exception {
        executeSync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM notification_outbox WHERE status IN ('sent', 'failed', 'expired', 'cancelled', 'not_enqueued') AND created_at < ?")) {
                ps.setString(1, cutoffIso);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM telegram_chat_activity WHERE last_attempt_at < ?")) {
                ps.setString(1, cutoffIso);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public ObjectNode loadNotificationHistorySync(int limit, int offset) throws Exception {
        return executeSync(() -> {
            ObjectNode result = json.createObjectNode();
            ArrayNode items = result.putArray("items");

            int total = 0;
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM notification_outbox")) {
                if (rs.next()) total = rs.getInt(1);
            }
            result.put("total", total);
            result.put("limit", limit);
            result.put("offset", offset);

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM notification_outbox ORDER BY sequence DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(mapOutboxItem(rs));
                    }
                }
            }

            ObjectNode summary = result.putObject("summary");
            Map<String, Integer> counts = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT status, COUNT(*) as cnt FROM notification_outbox GROUP BY status")) {
                while (rs.next()) {
                    counts.put(rs.getString("status"), rs.getInt("cnt"));
                }
            }
            summary.put("pending", counts.getOrDefault("pending", 0));
            summary.put("sending", counts.getOrDefault("sending", 0));
            summary.put("sent", counts.getOrDefault("sent", 0));
            summary.put("failed", counts.getOrDefault("failed", 0));
            summary.put("expired", counts.getOrDefault("expired", 0));
            summary.put("cancelled", counts.getOrDefault("cancelled", 0));
            summary.put("notEnqueued", counts.getOrDefault("not_enqueued", 0));

            String oldestPending = null;
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MIN(created_at) FROM notification_outbox WHERE status = 'pending'")) {
                if (rs.next()) oldestPending = rs.getString(1);
            }
            if (oldestPending != null) summary.put("oldestPendingAt", oldestPending);
            else summary.putNull("oldestPendingAt");

            return result;
        });
    }

    private ObjectNode mapOutboxItemForWorker(ResultSet rs) throws SQLException {
        ObjectNode item = json.createObjectNode();
        item.put("sequence", rs.getLong("sequence"));
        item.put("id", rs.getString("id"));
        String bId = rs.getString("backend_id");
        if (bId != null) item.put("backendId", bId); else item.putNull("backendId");
        String incId = rs.getString("incident_id");
        if (incId != null) item.put("incidentId", incId); else item.putNull("incidentId");
        item.put("type", rs.getString("type"));
        item.put("chatId", rs.getString("chat_id"));
        item.put("message", rs.getString("message"));
        item.put("status", rs.getString("status"));
        item.put("attempts", rs.getInt("attempts"));
        item.put("createdAt", rs.getString("created_at"));
        item.put("nextAttemptAt", rs.getString("next_attempt_at"));
        String lastAttemptAt = rs.getString("last_attempt_at");
        if (lastAttemptAt != null) item.put("lastAttemptAt", lastAttemptAt); else item.putNull("lastAttemptAt");
        String sentAt = rs.getString("sent_at");
        if (sentAt != null) item.put("sentAt", sentAt); else item.putNull("sentAt");
        String lastError = rs.getString("last_error");
        if (lastError != null) item.put("lastError", lastError); else item.putNull("lastError");
        return item;
    }

    private ObjectNode mapOutboxItem(ResultSet rs) throws SQLException {
        ObjectNode item = json.createObjectNode();
        item.put("id", rs.getString("id"));
        String bId = rs.getString("backend_id");
        if (bId != null) item.put("backendId", bId); else item.putNull("backendId");
        String incId = rs.getString("incident_id");
        if (incId != null) item.put("incidentId", incId); else item.putNull("incidentId");
        item.put("type", rs.getString("type"));
        item.put("chatId", rs.getString("chat_id"));
        item.put("status", rs.getString("status"));
        item.put("attempts", rs.getInt("attempts"));
        item.put("createdAt", rs.getString("created_at"));
        item.put("nextAttemptAt", rs.getString("next_attempt_at"));
        String sentAt = rs.getString("sent_at");
        if (sentAt != null) item.put("sentAt", sentAt); else item.putNull("sentAt");
        String lastError = rs.getString("last_error");
        if (lastError != null) item.put("lastError", lastError); else item.putNull("lastError");
        return item;
    }

    public void close(String lastDate, String lastStateJson) {
        Future<?> finalCheckpointFuture = null;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            if (lastDate != null && lastStateJson != null) {
                try {
                    finalCheckpointFuture = writer.submit(() -> {
                        try {
                            saveCheckpointInternal(lastDate, lastStateJson);
                        } catch (Exception e) {
                            LOG.error("Fallo al guardar checkpoint final", e);
                        }
                    });
                } catch (RejectedExecutionException ignored) {}
            }
        }

        if (finalCheckpointFuture != null) {
            try {
                finalCheckpointFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.error("Error durante checkpoint de cierre", e);
            }
        }

        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
        }
    }

    private interface SqlCallable<T> {
        T call() throws Exception;
    }

    private <T> T executeSync(SqlCallable<T> callable) throws Exception {
        Future<T> future;
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("StorageService está cerrado");
            try {
                future = writer.submit(() -> {
                    try {
                        return callable.call();
                    } catch (Exception e) {
                        healthy = false;
                        throw e;
                    }
                });
            } catch (RejectedExecutionException e) {
                throw new IllegalStateException("StorageService está cerrado o apagándose", e);
            }
        }
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }
}
