package pe.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.jboss.logging.Logger;
import static pe.gateway.StorageService.formatIso;

@ApplicationScoped
public class GatewayState {
    public record ProbeSample(String time, boolean online, int status, double latencyMs) {}
    public record MonitoringConfig(int probeIntervalSeconds, int probeTimeoutMs, int failureThreshold, int recoveryThreshold) {}
    public record TelegramConfig(boolean enabled, String chatId) {}

    static class BackendProbeControl {
        final AtomicBoolean slot = new AtomicBoolean(false);
        final AtomicLong generation = new AtomicLong(1);
        final Object lock = new Object();
        ScheduledFuture<?> scheduledFuture;
    }

    private static final ZoneId ZONE = ZoneId.of("America/Lima");
    private volatile Clock clock = Clock.system(ZONE);
    private String currentDate;

    @Inject ObjectMapper json;
    @Inject GatewayConfig config;
    @Inject HostMetricsService hostMetrics;
    @Inject StorageService storage;

    private static final Logger LOG = Logger.getLogger(GatewayState.class);
    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    // In-memory caches and locks
    private final Object routeLock = new Object();
    private final Object metricsLock = new Object();

    private volatile List<ObjectNode> routes = new ArrayList<>();
    private final List<ObjectNode> incidents = new ArrayList<>();
    private final Deque<ObjectNode> logs = new ArrayDeque<>();
    private final Map<String, Stats> stats = new HashMap<>();

    private volatile MonitoringConfig globalMonitoring = new MonitoringConfig(30, 2000, 3, 2);
    private volatile TelegramConfig globalTelegram = new TelegramConfig(false, null);

    private final BroadcastProcessor<String> streamBus = BroadcastProcessor.create();
    private ScheduledExecutorService timer;
    private ExecutorService probeWorkerExecutor;
    private ScheduledExecutorService checkpointScheduler;
    private ScheduledExecutorService telegramWorkerExecutor;
    private final ConcurrentHashMap<String, BackendProbeControl> probeControls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastChatAttemptEpochMs = new ConcurrentHashMap<>();
    private final AtomicBoolean wakeScheduled = new AtomicBoolean(false);
    private final Deque<Long> globalSendTimestamps = new ArrayDeque<>();
    private volatile long lastMaintenanceEpochMs = 0L;

    private volatile String forcedTelegramBaseUrlForTesting = null;
    private volatile HttpClient customTelegramHttpClient = null;

    private Path directory;
    private volatile boolean started = false;

    public void setClock(Clock clock) {
        this.clock = clock != null ? clock : Clock.system(ZONE);
    }

    public Clock getClock() {
        return clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public long nowMs() {
        return clock.millis();
    }

    public LocalDate todayDate() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    private final ConcurrentHashMap<String, String> cachedApiKeys = new ConcurrentHashMap<>();
    private volatile String cachedTelegramBotToken = null;

    public record RouteSnapshot(ObjectNode route, String apiKey) {}
    private volatile List<RouteSnapshot> routeSnapshots = List.of();

    private void refreshRouteSnapshots() {
        List<RouteSnapshot> list = new ArrayList<>();
        for (ObjectNode r : routes) {
            String id = r.path("id").asText();
            String key = cachedApiKeys.get(id);
            list.add(new RouteSnapshot(r.deepCopy(), key));
        }
        this.routeSnapshots = List.copyOf(list);
    }

    public RouteSnapshot matchRoute(String value, boolean auth) {
        List<RouteSnapshot> current = this.routeSnapshots;
        RouteSnapshot best = null;
        int length = -1;
        for (RouteSnapshot rs : current) {
            ObjectNode r = rs.route();
            if (!r.path("enabled").asBoolean()) continue;
            for (JsonNode prefix : r.path(auth ? "authPrefixes" : "aclPrefixes")) {
                if (value.startsWith(prefix.asText()) && prefix.asText().length() > length) {
                    best = rs;
                    length = prefix.asText().length();
                }
            }
        }
        return best;
    }

    public boolean isTelegramTokenConfigured() {
        String token = this.cachedTelegramBotToken;
        return token != null && !token.isBlank();
    }

    public String getEffectiveTelegramApiUrl() {
        boolean isTestProfile = io.quarkus.runtime.configuration.ConfigUtils.getProfiles().contains("test")
                || io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST;
        if (isTestProfile) {
            if (forcedTelegramBaseUrlForTesting != null && !forcedTelegramBaseUrlForTesting.isBlank()) {
                return forcedTelegramBaseUrlForTesting;
            }
            String configured = config.telegram().map(t -> t.apiUrl()).orElse("https://api.telegram.org");
            return (configured != null && !configured.isBlank()) ? configured : "https://api.telegram.org";
        }
        return "https://api.telegram.org";
    }

    void setTelegramBaseUrlForTesting(String url) {
        this.forcedTelegramBaseUrlForTesting = url;
    }

    String getResolvedTelegramApiUrlForTesting() {
        return getEffectiveTelegramApiUrl();
    }

    void setTelegramClientForTesting(HttpClient client) {
        this.customTelegramHttpClient = client;
    }

    private HttpClient getTelegramHttpClient() {
        return customTelegramHttpClient != null ? customTelegramHttpClient : this.client;
    }

    static class Stats {
        final Deque<Double> samples = new ArrayDeque<>();
        final Deque<ProbeSample> probeHistory = new ArrayDeque<>();
        double current;
        long count;
        double total;
        double min = Double.MAX_VALUE;
        double max;
        long observed;
        long lastProbe;
        long upMs;
        long downMs;
        boolean online = true;
        boolean known = false;
        boolean awaitingObservation = true;
        int consecutiveFailures = 0;
        int consecutiveSuccesses = 0;
        long firstFailureTime;
        long firstSuccessTime;
        String pendingState = null;
        boolean probeOnline;
        double probeMs;
        int probeHttpStatus;
    }

    void start(@Observes StartupEvent event) throws Exception {
        directory = Path.of(config.dataDir());
        storage.init();

        if (!storage.isImportedOrSeeded()) {
            Path routesFile = directory.resolve("routes.json");
            Path incidentsFile = directory.resolve("incidents.json");
            Path dailyStateFile = directory.resolve("daily-state.json");

            if (Files.exists(routesFile) || Files.exists(incidentsFile) || Files.exists(dailyStateFile)) {
                LOG.info("Detectados archivos legados JSON; iniciando validación estricta e importación atómica a SQLite");
                List<ObjectNode> importedRoutes = new ArrayList<>();
                List<ObjectNode> importedIncidents = new ArrayList<>();
                Map<String, String> legacyKeysToImport = new LinkedHashMap<>();
                String dailyStateDate = null;
                String dailyStateJson = null;

                if (!Files.exists(routesFile)) {
                    throw new IllegalStateException("routes.json es obligatorio para la migración de archivos legados");
                }
                JsonNode routesTree;
                try {
                    routesTree = json.readTree(Files.readString(routesFile));
                } catch (Exception e) {
                    throw new IllegalStateException("Archivo routes.json corrupto o inválido (error de sintaxis JSON)");
                }
                if (routesTree == null || !routesTree.isArray()) {
                    throw new IllegalStateException("Archivo routes.json debe contener un array JSON");
                }
                for (JsonNode rn : routesTree) {
                    if (!rn.isObject()) throw new IllegalStateException("Ruta inválida en routes.json: no es un objeto JSON");
                    ObjectNode route = ((ObjectNode) rn).deepCopy();
                    String id = route.path("id").asText();
                    validateBasicRouteFields(route);

                    String secretToImport = null;
                    if (route.has("apiKey") && !route.get("apiKey").isNull()) {
                        JsonNode kNode = route.get("apiKey");
                        if (!kNode.isTextual()) {
                            throw new IllegalStateException("Campo apiKey en ruta " + id + " de routes.json debe ser un string");
                        }
                        String raw = kNode.asText();
                        if (!raw.isEmpty()) {
                            validateHttpHeaderValue("apiKey", raw);
                            secretToImport = raw;
                        }
                    }

                    if (route.has("apiKeyEnv") && !route.get("apiKeyEnv").isNull()) {
                        JsonNode envNode = route.get("apiKeyEnv");
                        if (!envNode.isTextual()) {
                            throw new IllegalStateException("Campo apiKeyEnv en ruta " + id + " de routes.json debe ser un string");
                        }
                        String envVar = envNode.asText();
                        if (secretToImport == null && !envVar.isBlank()) {
                            String envVal = System.getenv(envVar);
                            if (envVal == null || envVal.isBlank()) {
                                throw new IllegalStateException("Fallo en importación legacy: la variable de entorno '" + envVar + "' referenciada por la ruta '" + id + "' no está definida o está vacía");
                            }
                            validateHttpHeaderValue("apiKey", envVal);
                            secretToImport = envVal;
                        }
                    }

                    if (secretToImport != null && !secretToImport.isBlank()) {
                        legacyKeysToImport.put("backend:" + id, secretToImport);
                    }

                    route.remove("apiKey");
                    route.remove("apiKeyEnv");
                    route.remove("apiKeyEnvMissing");
                    route.putNull("apiKeyEnv");
                    importedRoutes.add(route);
                }
                validatePrefixUniqueness(importedRoutes);

                if (Files.exists(incidentsFile)) {
                    JsonNode incTree;
                    try {
                        incTree = json.readTree(Files.readString(incidentsFile));
                    } catch (Exception e) {
                        throw new IllegalStateException("Archivo incidents.json corrupto o inválido (error de sintaxis JSON)");
                    }
                    if (incTree == null || !incTree.isArray()) {
                        throw new IllegalStateException("Archivo incidents.json debe contener un array JSON");
                    }
                    Set<String> incIds = new HashSet<>();
                    for (JsonNode in : incTree) {
                        if (!in.isObject()) throw new IllegalStateException("Elemento inválido en incidents.json: no es un objeto");
                        ObjectNode inc = ((ObjectNode) in).deepCopy();
                        String incId = inc.path("id").asText();
                        if (incId.isBlank()) throw new IllegalStateException("Incidente con ID vacío en incidents.json");
                        if (!incIds.add(incId)) throw new IllegalStateException("ID de incidente duplicado en incidents.json: " + incId);
                        if (inc.path("backendId").asText().isBlank()) throw new IllegalStateException("Incidente " + incId + " backendId vacío");
                        if (inc.path("backend").asText().isBlank()) throw new IllegalStateException("Incidente " + incId + " backend vacío");
                        String startedAt = inc.path("startedAt").asText();
                        try {
                            Instant.parse(startedAt);
                        } catch (DateTimeParseException e) {
                            throw new IllegalStateException("Incidente " + incId + " startedAt inválido: " + startedAt);
                        }
                        if (inc.hasNonNull("endedAt")) {
                            try {
                                Instant.parse(inc.path("endedAt").asText());
                            } catch (DateTimeParseException e) {
                                throw new IllegalStateException("Incidente " + incId + " endedAt inválido");
                            }
                        }
                        if (inc.hasNonNull("durationMs") && inc.path("durationMs").asLong(-1) < 0) {
                            throw new IllegalStateException("Incidente " + incId + " durationMs negativo");
                        }
                        importedIncidents.add(inc);
                    }
                }

                if (Files.exists(dailyStateFile)) {
                    JsonNode dsTree;
                    try {
                        dsTree = json.readTree(Files.readString(dailyStateFile));
                    } catch (Exception e) {
                        throw new IllegalStateException("Archivo daily-state.json corrupto o inválido (error de sintaxis JSON)");
                    }
                    if (dsTree == null || !dsTree.isObject()) {
                        throw new IllegalStateException("Archivo daily-state.json debe ser un objeto JSON válido");
                    }
                    String dDate = dsTree.path("date").asText("");
                    if (!dDate.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                        throw new IllegalStateException("Archivo daily-state.json contiene fecha inválida o faltante: " + dDate);
                    }
                    if (dsTree.has("logs") && !dsTree.path("logs").isArray()) {
                        throw new IllegalStateException("Archivo daily-state.json campo logs debe ser un array");
                    }
                    if (dsTree.has("stats") && !dsTree.path("stats").isObject()) {
                        throw new IllegalStateException("Archivo daily-state.json campo stats debe ser un objeto");
                    }
                    dailyStateDate = dDate;
                    dailyStateJson = dsTree.toString();
                }

                storage.importLegacyData(importedRoutes, importedIncidents, dailyStateDate, dailyStateJson, legacyKeysToImport);

                backupRestricted(routesFile);
                if (Files.exists(incidentsFile)) backupRestricted(incidentsFile);
                if (Files.exists(dailyStateFile)) backupRestricted(dailyStateFile);

            } else {
                LOG.info("Base de datos y archivos legados vacíos; sembrando rutas iniciales desde YAML");
                List<ObjectNode> seededRoutes = new ArrayList<>();
                Map<String, String> seededSecrets = new LinkedHashMap<>();
                String[] names = {"IoT Core Service", "Device Gateway", "Telemetry Service"};
                String[] ids = {"core-service", "device-gw", "telemetry"};
                int count = config.auth().size();
                for (int i = 0; i < count; i++) {
                    var a = config.auth().get(i);
                    var b = i < config.acl().size() ? config.acl().get(i) : a;
                    String routeId = i < ids.length ? ids[i] : "service-" + (i + 1);
                    String routeName = i < names.length ? names[i] : "Service " + (i + 1);

                    ObjectNode route = json.createObjectNode().put("id", routeId).put("name", routeName)
                            .put("enabled", true).put("authUrl", a.url().toString())
                            .put("aclUrl", b.url().toString()).put("healthUrl", a.url().resolve("/").toString());
                    String env = a.apiKeyEnv().orElse("");
                    route.putNull("apiKeyEnv");
                    ArrayNode authP = route.putArray("authPrefixes");
                    for (String p : a.prefixes()) authP.add(p);
                    ArrayNode aclP = route.putArray("aclPrefixes");
                    for (String p : b.prefixes()) aclP.add(p);
                    route.put("createdAt", now().toString());
                    route.putNull("monitoring");
                    route.putNull("telegram");

                    if (!env.isBlank()) {
                        String val = System.getenv(env);
                        if (val == null || val.isBlank()) {
                            throw new IllegalStateException("Fallo en siembra inicial: la variable de entorno '" + env + "' requerida por la ruta '" + routeId + "' no está configurada o está vacía");
                        }
                        validateHttpHeaderValue("apiKey", val);
                        seededSecrets.put("backend:" + routeId, val);
                    }
                    seededRoutes.add(route);
                }
                validatePrefixUniqueness(seededRoutes);
                storage.seedRoutes(seededRoutes, seededSecrets);
            }
        }

        // Cargar o sembrar configuración global de monitoreo con validación estricta
        String monJson = storage.loadSetting("monitoring");
        if (monJson != null && !monJson.isBlank()) {
            try {
                JsonNode mn = json.readTree(monJson);
                if (!mn.isObject()) throw new IllegalStateException("monitoring setting en DB no es un objeto JSON");
                int interval = parseIntStrict(mn.get("probeIntervalSeconds"), "probeIntervalSeconds", 1, 86400);
                int timeout = parseIntStrict(mn.get("probeTimeoutMs"), "probeTimeoutMs", 100, 60000);
                int failTh = parseIntStrict(mn.get("failureThreshold"), "failureThreshold", 1, 100);
                int recTh = parseIntStrict(mn.get("recoveryThreshold"), "recoveryThreshold", 1, 100);
                if (timeout >= interval * 1000) {
                    throw new IllegalStateException("monitoring setting en DB tiene timeout >= intervalo: " + timeout + " >= " + (interval * 1000));
                }
                globalMonitoring = new MonitoringConfig(interval, timeout, failTh, recTh);
            } catch (Exception e) {
                throw new IllegalStateException("Configuración de monitoreo en SQLite corrupta o inválida: " + e.getMessage(), e);
            }
        } else {
            int interval = config.monitoring().flatMap(m -> m.probeIntervalSeconds()).orElse(config.probeSeconds());
            int timeout = config.monitoring().flatMap(m -> m.probeTimeoutMs()).orElse((int) config.timeoutMs());
            int failTh = config.monitoring().flatMap(m -> m.failureThreshold()).orElse(3);
            int recTh = config.monitoring().flatMap(m -> m.recoveryThreshold()).orElse(2);

            if (interval < 1 || interval > 86400) {
                throw new IllegalStateException("Valor inicial inválido para probeIntervalSeconds: " + interval);
            }
            if (timeout < 100 || timeout > 60000) {
                throw new IllegalStateException("Valor inicial inválido para probeTimeoutMs: " + timeout);
            }
            if (failTh < 1 || failTh > 100) {
                throw new IllegalStateException("Valor inicial inválido para failureThreshold: " + failTh);
            }
            if (recTh < 1 || recTh > 100) {
                throw new IllegalStateException("Valor inicial inválido para recoveryThreshold: " + recTh);
            }
            if (timeout >= interval * 1000) {
                throw new IllegalStateException("Configuración inicial de monitoreo tiene timeout >= intervalo: " + timeout + " >= " + (interval * 1000));
            }

            globalMonitoring = new MonitoringConfig(interval, timeout, failTh, recTh);
            ObjectNode monNode = json.createObjectNode();
            monNode.put("probeIntervalSeconds", interval);
            monNode.put("probeTimeoutMs", timeout);
            monNode.put("failureThreshold", failTh);
            monNode.put("recoveryThreshold", recTh);
            storage.saveSettingSync("monitoring", monNode.toString());
        }

        // Cargar o sembrar configuración de Telegram
        String telJson = storage.loadSetting("telegram");
        if (telJson != null && !telJson.isBlank()) {
            try {
                JsonNode tn = json.readTree(telJson);
                boolean telEnabled = tn.path("enabled").asBoolean(false);
                String telChatId = tn.hasNonNull("chatId") ? tn.path("chatId").asText() : null;
                globalTelegram = new TelegramConfig(telEnabled, telChatId);
            } catch (Exception e) {
                LOG.warn("Error al parsear telegram settings desde SQLite; usando defaults", e);
                globalTelegram = new TelegramConfig(false, null);
            }
        } else {
            globalTelegram = new TelegramConfig(false, null);
            ObjectNode telNode = json.createObjectNode();
            telNode.put("enabled", false);
            telNode.putNull("chatId");
            storage.saveSettingSync("telegram", telNode.toString());
        }

        // Cargar secretos cifrados en caché privada en RAM antes de publicar rutas
        Map<String, String> allSecrets = storage.loadAllSecretsSync();
        for (Map.Entry<String, String> entry : allSecrets.entrySet()) {
            if (entry.getKey().startsWith("backend:")) {
                cachedApiKeys.put(entry.getKey().substring("backend:".length()), entry.getValue());
            } else if ("telegram:bot-token".equals(entry.getKey())) {
                cachedTelegramBotToken = entry.getValue();
            }
        }

        // Cargar rutas e incidentes desde SQLite
        routes = storage.loadRoutes();
        for (ObjectNode r : routes) {
            sanitizeRoute(r);
            MonitoringConfig eff = computeEffectiveConfig(r.get("monitoring"), globalMonitoring);
            if (eff.probeTimeoutMs() >= eff.probeIntervalSeconds() * 1000) {
                throw new IllegalStateException("Ruta '" + r.path("id").asText() + "' tiene timeout >= intervalo efectivo (" +
                        eff.probeTimeoutMs() + " >= " + (eff.probeIntervalSeconds() * 1000) + ")");
            }
        }
        refreshRouteSnapshots();

        incidents.clear();
        incidents.addAll(storage.loadIncidents());

        loadDailyStateFromCheckpoint();

        Set<String> openIncidentBackendIds = new HashSet<>();
        for (ObjectNode inc : incidents) {
            if (inc.path("endedAt").isNull()) {
                openIncidentBackendIds.add(inc.path("backendId").asText());
            }
        }

        synchronized (metricsLock) {
            for (ObjectNode r : routes) {
                String id = r.path("id").asText();
                Stats s = stats.computeIfAbsent(id, k -> new Stats());
                s.consecutiveFailures = 0;
                s.consecutiveSuccesses = 0;
                s.pendingState = null;
                s.awaitingObservation = true;
                s.known = false;
                s.online = !openIncidentBackendIds.contains(id);
            }
        }

        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-probe-timer");
            t.setDaemon(true);
            return t;
        });
        probeWorkerExecutor = Executors.newVirtualThreadPerTaskExecutor();

        for (ObjectNode r : routes) {
            String id = r.path("id").asText();
            BackendProbeControl ctrl = probeControls.computeIfAbsent(id, k -> new BackendProbeControl());
            if (r.path("enabled").asBoolean()) {
                scheduleProbeLocked(id, ctrl, 100);
            }
        }

        checkpointScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-checkpoints"); t.setDaemon(true); return t;
        });
        checkpointScheduler.scheduleWithFixedDelay(this::saveDailyState, 30, 30, TimeUnit.SECONDS);

        // Inicializar worker y persistencia de Telegram
        Map<String, String> persistedChatActivity = storage.loadAllChatActivitySync();
        for (Map.Entry<String, String> entry : persistedChatActivity.entrySet()) {
            try {
                lastChatAttemptEpochMs.put(entry.getKey(), Instant.parse(entry.getValue()).toEpochMilli());
            } catch (Exception ignored) {}
        }
        storage.recoverOrphanedSendingSync();
        storage.purgeTerminalOutboxSync(formatIso(nowMs() - 30L * 86400000L));

        telegramWorkerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telegram-dispatcher");
            t.setDaemon(true);
            return t;
        });
        telegramWorkerExecutor.scheduleWithFixedDelay(this::dispatchOutboxLoop, 1, 1, TimeUnit.SECONDS);

        started = true;
    }

    private void backupRestricted(Path file) {
        try {
            Path bak = file.resolveSibling(file.getFileName().toString() + ".bak");
            Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            StorageService.setRestrictivePermissions(bak, false);
        } catch (Exception e) {
            LOG.warn("No se pudo crear backup de " + file.getFileName(), e);
        }
    }

    void stop(@Observes ShutdownEvent event) {
        if (timer != null) timer.shutdownNow();
        if (probeWorkerExecutor != null) probeWorkerExecutor.shutdownNow();
        try {
            if (timer != null) timer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        try {
            if (probeWorkerExecutor != null) probeWorkerExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        if (telegramWorkerExecutor != null) {
            telegramWorkerExecutor.shutdownNow();
            try {
                if (!telegramWorkerExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    telegramWorkerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                telegramWorkerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (checkpointScheduler != null) {
            checkpointScheduler.shutdownNow();
            try {
                checkpointScheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
        if (!started) {
            storage.close(null, null);
            return;
        }
        String finalJson = captureDailyStateJson();
        storage.close(currentDate != null ? currentDate : todayDate().toString(), finalJson);
    }

    public void sanitizeRoute(ObjectNode route) {
        route.remove("apiKey");
        route.remove("apiKeyEnv");
        route.remove("apiKeyEnvMissing");
        String id = route.path("id").asText();
        String key = cachedApiKeys.get(id);
        boolean hasApiKey = key != null && !key.isBlank();
        route.put("hasApiKey", hasApiKey);
    }

    public static void validateHttpHeaderValue(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException("El campo '" + name + "' no puede estar vacío ni contener únicamente espacios en blanco");
        }
        if (value.length() > 4096) {
            throw new BadRequestException("El campo '" + name + "' excede el límite máximo de 4096 caracteres");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new BadRequestException("El campo '" + name + "' contiene caracteres no permitidos en cabeceras HTTP (controles o no-ASCII)");
            }
        }
    }

    public static void validateTelegramBotToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("El campo 'botToken' no puede estar vacío");
        }
        if (token.length() > 4096) {
            throw new BadRequestException("El campo 'botToken' excede el límite máximo de 4096 caracteres");
        }
        if (!token.matches("^[a-zA-Z0-9_:\\-]+$")) {
            throw new BadRequestException("El campo 'botToken' contiene caracteres no permitidos para un segmento URL de Telegram");
        }
    }

    private static int parseIntStrict(JsonNode node, String fieldName, int min, int max) {
        if (node == null || node.isNull()) {
            throw new BadRequestException("El campo '" + fieldName + "' es requerido");
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new BadRequestException("El campo '" + fieldName + "' debe ser un número entero válido (sin overflow)");
        }
        long val = node.asLong();
        if (val < min || val > max) {
            throw new BadRequestException("El campo '" + fieldName + "' debe estar entre " + min + " y " + max);
        }
        return (int) val;
    }

    private static Integer parseOptionalIntStrict(JsonNode node, String fieldName, int min, int max) {
        if (node == null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new BadRequestException("El campo '" + fieldName + "' debe ser un número entero válido (sin overflow)");
        }
        long val = node.asLong();
        if (val < min || val > max) {
            throw new BadRequestException("El campo '" + fieldName + "' debe estar entre " + min + " y " + max);
        }
        return (int) val;
    }

    public static MonitoringConfig computeEffectiveConfig(JsonNode routeMon, MonitoringConfig global) {
        int interval = global.probeIntervalSeconds();
        int timeout = global.probeTimeoutMs();
        int failTh = global.failureThreshold();
        int recTh = global.recoveryThreshold();

        if (routeMon != null && routeMon.isObject()) {
            if (routeMon.hasNonNull("probeIntervalSeconds")) {
                interval = routeMon.path("probeIntervalSeconds").asInt();
            }
            if (routeMon.hasNonNull("probeTimeoutMs")) {
                timeout = routeMon.path("probeTimeoutMs").asInt();
            }
            if (routeMon.hasNonNull("failureThreshold")) {
                failTh = routeMon.path("failureThreshold").asInt();
            }
            if (routeMon.hasNonNull("recoveryThreshold")) {
                recTh = routeMon.path("recoveryThreshold").asInt();
            }
        }
        return new MonitoringConfig(interval, timeout, failTh, recTh);
    }

    public ObjectNode computeEffectiveMonitoring(JsonNode routeMon) {
        MonitoringConfig eff = computeEffectiveConfig(routeMon, globalMonitoring);
        ObjectNode node = json.createObjectNode();
        node.put("probeIntervalSeconds", eff.probeIntervalSeconds());
        node.put("probeTimeoutMs", eff.probeTimeoutMs());
        node.put("failureThreshold", eff.failureThreshold());
        node.put("recoveryThreshold", eff.recoveryThreshold());
        return node;
    }

    public static TelegramConfig computeEffectiveTelegram(JsonNode routeTel, TelegramConfig global) {
        boolean routeEnabled = true;
        String effChatId = (global != null) ? global.chatId() : null;

        if (routeTel != null && routeTel.isObject()) {
            if (routeTel.hasNonNull("enabled")) {
                routeEnabled = routeTel.path("enabled").asBoolean();
            }
            if (routeTel.hasNonNull("chatId")) {
                String cId = routeTel.path("chatId").asText().trim();
                if (!cId.isEmpty()) effChatId = cId;
            }
        }

        boolean masterEnabled = (global != null) && global.enabled();
        boolean finalEnabled = masterEnabled && routeEnabled;
        return new TelegramConfig(finalEnabled, effChatId);
    }

    public ObjectNode computeEffectiveTelegramNode(JsonNode routeTel) {
        TelegramConfig eff = computeEffectiveTelegram(routeTel, globalTelegram);
        ObjectNode node = json.createObjectNode();
        node.put("enabled", eff.enabled());
        if (eff.chatId() != null) node.put("chatId", eff.chatId());
        else node.putNull("chatId");
        return node;
    }

    public void enrichRoute(ObjectNode route) {
        sanitizeRoute(route);
        JsonNode mon = route.get("monitoring");
        if (mon == null || mon.isNull()) {
            route.putNull("monitoring");
        }
        route.set("effectiveMonitoring", computeEffectiveMonitoring(mon));

        JsonNode tel = route.get("telegram");
        if (tel == null || tel.isNull()) {
            route.putNull("telegram");
        }
        route.set("effectiveTelegram", computeEffectiveTelegramNode(tel));
    }

    private void validateBasicRouteFields(JsonNode route) {
        if (!route.isObject() || !route.path("name").isTextual() || route.path("name").asText().isBlank()
                || !route.path("enabled").isBoolean()) throw new BadRequestException("Name and enabled are required");
        for (String key : List.of("authUrl", "aclUrl", "healthUrl")) {
            try {
                URI uri = URI.create(route.path(key).asText());
                if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            } catch (Exception e) { throw new BadRequestException("Invalid " + key); }
        }
        for (String key : List.of("authPrefixes", "aclPrefixes")) {
            JsonNode prefixes = route.path(key);
            if (!prefixes.isArray() || prefixes.isEmpty()) throw new BadRequestException("Prefixes required");
            for (JsonNode prefix : prefixes) if (!prefix.isTextual() || prefix.asText().isBlank())
                throw new BadRequestException("Invalid prefix");
        }
    }

    private void validate(JsonNode route) {
        validateBasicRouteFields(route);
        if (route.has("apiKey")) {
            JsonNode keyNode = route.get("apiKey");
            if (keyNode == null || keyNode.isNull() || !keyNode.isTextual()) {
                throw new BadRequestException("El campo 'apiKey' debe ser una cadena de texto");
            }
            String val = keyNode.asText();
            if (!val.isEmpty()) {
                validateHttpHeaderValue("apiKey", val);
            }
        }
        if (route.hasNonNull("apiKeyEnv") && !route.path("apiKeyEnv").asText().isBlank()) {
            throw new BadRequestException("El uso de 'apiKeyEnv' ha sido reemplazado por secretos administrados cifrados. Configure la clave directamente mediante el campo 'apiKey'.");
        }
    }

    private void validatePrefixUniqueness(List<ObjectNode> routeList) {
        Set<String> auth = new HashSet<>(), acl = new HashSet<>();
        for (JsonNode r : routeList) {
            if (r.path("enabled").asBoolean()) {
                for (String key : List.of("authPrefixes", "aclPrefixes")) {
                    for (JsonNode prefix : r.path(key)) {
                        if (!(key.equals("authPrefixes") ? auth : acl).add(prefix.asText())) {
                            throw new BadRequestException("Duplicate prefix: " + prefix.asText());
                        }
                    }
                }
            }
        }
    }

    private void checkDateRotation() {
        synchronized (metricsLock) {
            String today = todayDate().toString();
            if (currentDate == null) {
                currentDate = today;
                return;
            }
            if (!today.equals(currentDate)) {
                LOG.infof("Midnight rollover: archiving day %s, starting %s", currentDate, today);
                currentDate = today;
                logs.clear();
                for (Stats s : stats.values()) {
                    s.samples.clear();
                    s.probeHistory.clear();
                    s.count = 0;
                    s.total = 0;
                    s.min = Double.MAX_VALUE;
                    s.max = 0;
                    s.upMs = 0;
                    s.downMs = 0;
                    s.observed = nowMs();
                }
                saveDailyState();
                notifyUpdate();
            }
        }
    }

    private void loadDailyStateFromCheckpoint() {
        synchronized (metricsLock) {
            try {
                String today = todayDate().toString();
                currentDate = today;
                String savedJson = storage.loadCheckpoint(today);
                if (savedJson == null || savedJson.isBlank()) return;

                JsonNode root = json.readTree(savedJson);
                if (root == null || !root.isObject()) return;
                String savedDate = safe(root, "date");
                if (!today.equals(savedDate)) {
                    LOG.infof("Discarding previous day data from %s (today is %s)", savedDate, today);
                    return;
                }
                logs.clear();
                if (root.path("logs").isArray()) {
                    for (JsonNode l : root.path("logs")) {
                        if (l.isObject() && logs.size() < 2000) logs.add(((ObjectNode) l).deepCopy());
                    }
                }
                JsonNode statsNode = root.path("stats");
                if (statsNode.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = statsNode.fields();
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        String id = entry.getKey();
                        JsonNode sn = entry.getValue();
                        Stats s = stats.computeIfAbsent(id, k -> new Stats());
                        s.count = sn.path("count").asLong(0);
                        s.total = sn.path("total").asDouble(0);
                        s.min = sn.path("min").asDouble(Double.MAX_VALUE);
                        s.max = sn.path("max").asDouble(0);
                        s.upMs = sn.path("upMs").asLong(0);
                        s.downMs = sn.path("downMs").asLong(0);
                        s.observed = sn.path("observed").asLong(0);
                        s.lastProbe = sn.path("lastProbe").asLong(0);
                        s.online = sn.path("online").asBoolean(true);
                        s.known = sn.path("known").asBoolean(false);
                        s.probeOnline = sn.path("probeOnline").asBoolean(true);
                        s.probeMs = sn.path("probeMs").asDouble(0);
                        s.probeHttpStatus = sn.path("probeHttpStatus").asInt(200);

                        s.samples.clear();
                        if (sn.path("samples").isArray()) {
                            for (JsonNode sm : sn.path("samples")) {
                                if (s.samples.size() < 2000) s.samples.add(sm.asDouble());
                            }
                        }
                        s.probeHistory.clear();
                        if (sn.path("probes").isArray()) {
                            for (JsonNode p : sn.path("probes")) {
                                if (s.probeHistory.size() < 2880) {
                                    s.probeHistory.add(new ProbeSample(
                                            safe(p, "time"),
                                            p.path("online").asBoolean(true),
                                            p.path("status").asInt(200),
                                            p.path("latencyMs").asDouble(0)
                                    ));
                                }
                            }
                        }
                    }
                }
                LOG.infof("Daily state restored from checkpoint for date %s (%d logs, %d backends)", today, logs.size(), stats.size());
            } catch (Exception e) {
                LOG.warn("No se pudo restaurar checkpoint diario", e);
            }
        }
    }

    private String captureDailyStateJson() {
        synchronized (metricsLock) {
            ObjectNode root = json.createObjectNode();
            root.put("date", currentDate != null ? currentDate : todayDate().toString());
            ArrayNode logsArr = root.putArray("logs");
            for (ObjectNode l : logs) logsArr.add(l.deepCopy());

            ObjectNode statsNode = root.putObject("stats");
            for (Map.Entry<String, Stats> entry : stats.entrySet()) {
                Stats s = entry.getValue();
                ObjectNode sn = statsNode.putObject(entry.getKey());
                sn.put("count", s.count).put("total", s.total).put("min", s.min).put("max", s.max)
                  .put("upMs", s.upMs).put("downMs", s.downMs).put("observed", s.observed)
                  .put("lastProbe", s.lastProbe).put("online", s.online).put("known", s.known)
                  .put("probeOnline", s.probeOnline).put("probeMs", s.probeMs).put("probeHttpStatus", s.probeHttpStatus);
                ArrayNode smArr = sn.putArray("samples");
                for (Double d : s.samples) smArr.add(d);
                ArrayNode pbArr = sn.putArray("probes");
                for (ProbeSample ps : s.probeHistory) {
                    pbArr.addObject()
                         .put("time", ps.time())
                         .put("online", ps.online())
                         .put("status", ps.status())
                         .put("latencyMs", ps.latencyMs());
                }
            }
            return root.toString();
        }
    }

    private void saveDailyState() {
        String stateJson = captureDailyStateJson();
        String date = currentDate != null ? currentDate : todayDate().toString();
        storage.saveCheckpointAsync(date, stateJson);
    }

    public ArrayNode routes() {
        return routes(false);
    }

    public ArrayNode routes(boolean secrets) {
        ArrayNode arr = json.createArrayNode();
        for (ObjectNode r : routes) {
            ObjectNode copy = r.deepCopy();
            enrichRoute(copy);
            arr.add(copy);
        }
        return arr;
    }

    public ObjectNode route(String id) {
        for (ObjectNode route : routes) {
            if (route.path("id").asText().equals(id)) {
                ObjectNode copy = route.deepCopy();
                enrichRoute(copy);
                return copy;
            }
        }
        throw new NotFoundException("Backend not found");
    }

    public ObjectNode settings() {
        MonitoringConfig gm;
        TelegramConfig gt;
        boolean tokenConfigured;
        synchronized (metricsLock) {
            gm = this.globalMonitoring;
            gt = this.globalTelegram;
            tokenConfigured = isTelegramTokenConfigured();
        }

        ObjectNode root = json.createObjectNode();
        ObjectNode mon = root.putObject("monitoring");
        mon.put("probeIntervalSeconds", gm.probeIntervalSeconds());
        mon.put("probeTimeoutMs", gm.probeTimeoutMs());
        mon.put("failureThreshold", gm.failureThreshold());
        mon.put("recoveryThreshold", gm.recoveryThreshold());

        ObjectNode tel = root.putObject("telegram");
        tel.put("enabled", gt.enabled());
        if (gt.chatId() != null) tel.put("chatId", gt.chatId());
        else tel.putNull("chatId");
        tel.put("tokenConfigured", tokenConfigured);

        return root;
    }

    public ObjectNode updateSettings(ObjectNode body) {
        synchronized (routeLock) {
            if (body == null) {
                throw new BadRequestException("Se requiere el cuerpo de configuración");
            }

            // Validar que solo se reciban claves permitidas
            Iterator<String> topFields = body.fieldNames();
            while (topFields.hasNext()) {
                String k = topFields.next();
                if (!"monitoring".equals(k) && !"telegram".equals(k)) {
                    throw new BadRequestException("Clave no permitida en settings: " + k);
                }
            }

            MonitoringConfig newMon = null;
            ObjectNode cleanMon = null;
            if (body.has("monitoring")) {
                JsonNode monNode = body.get("monitoring");
                if (monNode == null || !monNode.isObject()) {
                    throw new BadRequestException("El campo 'monitoring' debe ser un objeto JSON");
                }
                Iterator<String> monFields = monNode.fieldNames();
                Set<String> allowedMonFields = Set.of("probeIntervalSeconds", "probeTimeoutMs", "failureThreshold", "recoveryThreshold");
                while (monFields.hasNext()) {
                    String f = monFields.next();
                    if (!allowedMonFields.contains(f)) {
                        throw new BadRequestException("Clave no permitida en settings.monitoring: " + f);
                    }
                }

                int interval = parseIntStrict(monNode.get("probeIntervalSeconds"), "probeIntervalSeconds", 1, 86400);
                int timeout = parseIntStrict(monNode.get("probeTimeoutMs"), "probeTimeoutMs", 100, 60000);
                int failTh = parseIntStrict(monNode.get("failureThreshold"), "failureThreshold", 1, 100);
                int recTh = parseIntStrict(monNode.get("recoveryThreshold"), "recoveryThreshold", 1, 100);

                if (timeout >= interval * 1000) {
                    throw new BadRequestException("probeTimeoutMs (" + timeout + " ms) debe ser estrictamente menor a probeIntervalSeconds (" + (interval * 1000) + " ms)");
                }

                newMon = new MonitoringConfig(interval, timeout, failTh, recTh);

                List<String> conflictingIds = new ArrayList<>();
                for (ObjectNode r : routes) {
                    MonitoringConfig eff = computeEffectiveConfig(r.get("monitoring"), newMon);
                    if (eff.probeTimeoutMs() >= eff.probeIntervalSeconds() * 1000) {
                        conflictingIds.add(r.path("id").asText());
                    }
                }
                if (!conflictingIds.isEmpty()) {
                    throw new BadRequestException("Los nuevos settings globales invalidan las siguientes rutas (timeout >= intervalo): " + conflictingIds);
                }

                cleanMon = json.createObjectNode();
                cleanMon.put("probeIntervalSeconds", interval);
                cleanMon.put("probeTimeoutMs", timeout);
                cleanMon.put("failureThreshold", failTh);
                cleanMon.put("recoveryThreshold", recTh);
            }

            TelegramConfig newTel = null;
            ObjectNode cleanTel = null;
            String newBotTokenToPersist = null;
            boolean shouldDeleteBotToken = false;
            if (body.has("telegram")) {
                JsonNode telNode = body.get("telegram");
                if (telNode == null || !telNode.isObject()) {
                    throw new BadRequestException("El campo 'telegram' debe ser un objeto JSON");
                }
                Iterator<String> telFields = telNode.fieldNames();
                Set<String> allowedTelFields = Set.of("enabled", "chatId", "botToken");
                while (telFields.hasNext()) {
                    String f = telFields.next();
                    if (!allowedTelFields.contains(f)) {
                        throw new BadRequestException("Clave no permitida en settings.telegram: " + f);
                    }
                }

                if (!telNode.has("enabled") || telNode.get("enabled").isNull() || !telNode.get("enabled").isBoolean()) {
                    throw new BadRequestException("El campo 'enabled' en telegram es obligatorio y debe ser booleano");
                }
                boolean enabled = telNode.get("enabled").asBoolean();

                String chatId = null;
                if (telNode.has("chatId") && !telNode.get("chatId").isNull()) {
                    chatId = telNode.get("chatId").asText().trim();
                    if (chatId.isEmpty()) chatId = null;
                }

                if (telNode.has("botToken")) {
                    JsonNode tokenNode = telNode.get("botToken");
                    if (tokenNode == null || tokenNode.isNull() || !tokenNode.isTextual()) {
                        throw new BadRequestException("El campo 'botToken' debe ser una cadena de texto");
                    }
                    String rawTok = tokenNode.asText();
                    if (rawTok.isEmpty()) {
                        shouldDeleteBotToken = true;
                    } else {
                        validateTelegramBotToken(rawTok);
                        newBotTokenToPersist = rawTok;
                    }
                }

                boolean tokenWillExist = (newBotTokenToPersist != null) || (!shouldDeleteBotToken && isTelegramTokenConfigured());
                if (enabled) {
                    if (!tokenWillExist) {
                        throw new BadRequestException("No se puede habilitar Telegram sin un botToken configurado");
                    }
                    if (chatId == null || chatId.isBlank()) {
                        throw new BadRequestException("No se puede habilitar Telegram sin un chatId configurado");
                    }
                }
                if (shouldDeleteBotToken && enabled) {
                    throw new BadRequestException("No se puede eliminar el botToken si Telegram está habilitado. Deshabilite Telegram en la misma operación.");
                }

                newTel = new TelegramConfig(enabled, chatId);
                cleanTel = json.createObjectNode();
                cleanTel.put("enabled", enabled);
                if (chatId != null) cleanTel.put("chatId", chatId);
                else cleanTel.putNull("chatId");
            }

            boolean cancelAllPending = (newTel != null && this.globalTelegram.enabled() && !newTel.enabled());

            // Persistir de forma atómica en SQLite fuera de metricsLock
            Map<String, String> settingsMap = new HashMap<>();
            if (cleanMon != null) settingsMap.put("monitoring", cleanMon.toString());
            if (cleanTel != null) settingsMap.put("telegram", cleanTel.toString());

            try {
                storage.saveSettingsAtomicSync(settingsMap, cancelAllPending, newBotTokenToPersist, shouldDeleteBotToken);
            } catch (Exception e) {
                LOG.error("Fallo al persistir settings en SQLite: " + e.getClass().getSimpleName());
                throw new IllegalStateException("Error interno al persistir la configuración en el almacenamiento");
            }

            // Publicación atómica bajo metricsLock
            synchronized (metricsLock) {
                if (shouldDeleteBotToken) {
                    cachedTelegramBotToken = null;
                } else if (newBotTokenToPersist != null) {
                    cachedTelegramBotToken = newBotTokenToPersist;
                }

                MonitoringConfig oldGlobalMon = this.globalMonitoring;
                if (newMon != null) {
                    this.globalMonitoring = newMon;
                    for (ObjectNode r : routes) {
                        String id = r.path("id").asText();
                        MonitoringConfig oldEff = computeEffectiveConfig(r.get("monitoring"), oldGlobalMon);
                        MonitoringConfig newEff = computeEffectiveConfig(r.get("monitoring"), newMon);

                        boolean policyChanged = (oldEff.probeIntervalSeconds() != newEff.probeIntervalSeconds()
                                || oldEff.probeTimeoutMs() != newEff.probeTimeoutMs()
                                || oldEff.failureThreshold() != newEff.failureThreshold()
                                || oldEff.recoveryThreshold() != newEff.recoveryThreshold());

                        if (policyChanged) {
                            Stats s = stats.get(id);
                            if (s != null) {
                                s.consecutiveFailures = 0;
                                s.consecutiveSuccesses = 0;
                                s.pendingState = null;
                            }
                            BackendProbeControl ctrl = probeControls.computeIfAbsent(id, k -> new BackendProbeControl());
                            ctrl.generation.incrementAndGet();
                            if (r.path("enabled").asBoolean()) {
                                scheduleProbeLocked(id, ctrl, newEff.probeIntervalSeconds() * 1000L);
                            }
                        }
                    }
                }

                if (newTel != null) {
                    this.globalTelegram = newTel;
                }
            }

            notifyUpdate();
            return settings();
        }
    }

    public ObjectNode put(String id, ObjectNode input) {
        synchronized (routeLock) {
            ObjectNode next = input.deepCopy();
            ObjectNode old = null;
            if (id == null) {
                id = UUID.randomUUID().toString();
                next.put("createdAt", now().toString());
            } else {
                old = route(id);
                next.put("createdAt", old.path("createdAt").asText());
            }

            if (next.hasNonNull("apiKeyEnv") && !next.path("apiKeyEnv").asText().isBlank()) {
                throw new BadRequestException("El uso de 'apiKeyEnv' ha sido reemplazado por secretos administrados cifrados. Configure la clave directamente mediante el campo 'apiKey'.");
            }
            next.remove("apiKeyEnv");
            next.putNull("apiKeyEnv");

            String existingKey = (old != null) ? cachedApiKeys.get(id) : null;
            String newApiKeyToPersist = null;
            boolean shouldDeleteApiKey = false;
            boolean keyChanged = false;

            if (input.has("apiKey")) {
                JsonNode keyNode = input.get("apiKey");
                if (keyNode == null || keyNode.isNull() || !keyNode.isTextual()) {
                    throw new BadRequestException("El campo 'apiKey' debe ser una cadena de texto");
                }
                String rawKey = keyNode.asText();
                if (rawKey.isEmpty()) {
                    shouldDeleteApiKey = true;
                    keyChanged = (existingKey != null && !existingKey.isBlank());
                } else {
                    validateHttpHeaderValue("apiKey", rawKey);
                    newApiKeyToPersist = rawKey;
                    keyChanged = !Objects.equals(rawKey, existingKey);
                }
            }

            // Manejo de 'monitoring'
            if (old != null && !input.has("monitoring")) {
                if (old.hasNonNull("monitoring")) {
                    next.set("monitoring", old.get("monitoring").deepCopy());
                } else {
                    next.putNull("monitoring");
                }
            } else if (input.has("monitoring")) {
                JsonNode monInput = input.get("monitoring");
                if (monInput == null || monInput.isNull()) {
                    next.putNull("monitoring");
                } else if (!monInput.isObject()) {
                    throw new BadRequestException("El campo monitoring debe ser un objeto o null");
                } else {
                    ObjectNode cleaned = json.createObjectNode();
                    Integer valInterval = parseOptionalIntStrict(monInput.get("probeIntervalSeconds"), "probeIntervalSeconds", 1, 86400);
                    if (valInterval != null) cleaned.put("probeIntervalSeconds", valInterval);
                    else cleaned.putNull("probeIntervalSeconds");

                    Integer valTimeout = parseOptionalIntStrict(monInput.get("probeTimeoutMs"), "probeTimeoutMs", 100, 60000);
                    if (valTimeout != null) cleaned.put("probeTimeoutMs", valTimeout);
                    else cleaned.putNull("probeTimeoutMs");

                    Integer valFail = parseOptionalIntStrict(monInput.get("failureThreshold"), "failureThreshold", 1, 100);
                    if (valFail != null) cleaned.put("failureThreshold", valFail);
                    else cleaned.putNull("failureThreshold");

                    Integer valRec = parseOptionalIntStrict(monInput.get("recoveryThreshold"), "recoveryThreshold", 1, 100);
                    if (valRec != null) cleaned.put("recoveryThreshold", valRec);
                    else cleaned.putNull("recoveryThreshold");

                    MonitoringConfig eff = computeEffectiveConfig(cleaned, globalMonitoring);
                    if (eff.probeTimeoutMs() >= eff.probeIntervalSeconds() * 1000) {
                        throw new BadRequestException("El timeout de sondeo efectivo (" + eff.probeTimeoutMs() +
                                " ms) debe ser menor al intervalo de sondeo efectivo (" + (eff.probeIntervalSeconds() * 1000) + " ms)");
                    }
                    next.set("monitoring", cleaned);
                }
            } else {
                next.putNull("monitoring");
            }

            // Manejo de 'telegram'
            if (old != null && !input.has("telegram")) {
                if (old.hasNonNull("telegram")) {
                    next.set("telegram", old.get("telegram").deepCopy());
                } else {
                    next.putNull("telegram");
                }
            } else if (input.has("telegram")) {
                JsonNode telInput = input.get("telegram");
                if (telInput == null || telInput.isNull()) {
                    next.putNull("telegram");
                } else if (!telInput.isObject()) {
                    throw new BadRequestException("El campo telegram debe ser un objeto o null");
                } else {
                    ObjectNode cleaned = json.createObjectNode();
                    if (telInput.has("enabled") && !telInput.get("enabled").isNull()) {
                        if (!telInput.get("enabled").isBoolean()) {
                            throw new BadRequestException("El campo telegram.enabled debe ser booleano");
                        }
                        cleaned.put("enabled", telInput.get("enabled").asBoolean());
                    } else {
                        cleaned.putNull("enabled");
                    }
                    if (telInput.has("chatId") && !telInput.get("chatId").isNull()) {
                        String cId = telInput.get("chatId").asText().trim();
                        if (!cId.isEmpty()) cleaned.put("chatId", cId);
                        else cleaned.putNull("chatId");
                    } else {
                        cleaned.putNull("chatId");
                    }

                    if (cleaned.hasNonNull("enabled") && cleaned.path("enabled").asBoolean()) {
                        TelegramConfig effTel = computeEffectiveTelegram(cleaned, globalTelegram);
                        if (!isTelegramTokenConfigured()) {
                            throw new BadRequestException("No se puede habilitar Telegram en la ruta sin un bot-token configurado");
                        }
                        if (effTel.chatId() == null || effTel.chatId().isBlank()) {
                            throw new BadRequestException("No se puede habilitar Telegram en la ruta sin un chatId configurado");
                        }
                    }

                    next.set("telegram", cleaned);
                }
            } else {
                next.putNull("telegram");
            }

            next.retain("name", "enabled", "authUrl", "aclUrl", "healthUrl", "authPrefixes", "aclPrefixes", "apiKeyEnv", "createdAt", "apiKey", "monitoring", "telegram");
            next.put("id", id);
            validate(next);
            sanitizeRoute(next);

            List<ObjectNode> updated = new ArrayList<>();
            for (ObjectNode r : routes) {
                if (!r.path("id").asText().equals(id)) {
                    updated.add(r.deepCopy());
                }
            }
            updated.add(next.deepCopy());
            validatePrefixUniqueness(updated);

            boolean wasTelActive = old != null && computeEffectiveTelegram(old.get("telegram"), globalTelegram).enabled();
            boolean isTelActive = computeEffectiveTelegram(next.get("telegram"), globalTelegram).enabled();
            boolean cancelPendingForBackend = !next.path("enabled").asBoolean() || (wasTelActive && !isTelActive);

            ObjectNode closedIncident = null;
            if (!next.path("enabled").asBoolean()) {
                long now = nowMs();
                synchronized (metricsLock) {
                    for (ObjectNode inc : incidents) {
                        if (inc.path("backendId").asText().equals(id) && inc.path("endedAt").isNull()) {
                            closedIncident = inc.deepCopy();
                            closedIncident.put("endedAt", Instant.ofEpochMilli(now).toString());
                            long startMs = Instant.parse(inc.path("startedAt").asText()).toEpochMilli();
                            closedIncident.put("durationMs", Math.max(0, now - startMs));
                            closedIncident.put("endReason", "Monitoring paused");
                            break;
                        }
                    }
                }
            }

            // Persistir de forma atómica en SQLite fuera de metricsLock
            try {
                storage.saveRouteAtomicSync(next, closedIncident, cancelPendingForBackend, newApiKeyToPersist, shouldDeleteApiKey);
            } catch (Exception e) {
                LOG.error("Fallo al persistir la ruta en SQLite: " + e.getClass().getSimpleName());
                throw new IllegalStateException("Error interno al persistir la ruta en el almacenamiento");
            }

            if (shouldDeleteApiKey) {
                cachedApiKeys.remove(id);
            } else if (newApiKeyToPersist != null) {
                cachedApiKeys.put(id, newApiKeyToPersist);
            }

            routes = updated;
            refreshRouteSnapshots();

            // Publicación atómica bajo metricsLock
            synchronized (metricsLock) {
                if (closedIncident != null) {
                    for (ObjectNode inc : incidents) {
                        if (inc.path("id").asText().equals(closedIncident.path("id").asText())) {
                            inc.set("endedAt", closedIncident.get("endedAt"));
                            inc.set("durationMs", closedIncident.get("durationMs"));
                            inc.set("endReason", closedIncident.get("endReason"));
                            break;
                        }
                    }
                }

                BackendProbeControl ctrl = probeControls.computeIfAbsent(id, k -> new BackendProbeControl());
                ctrl.generation.incrementAndGet();

                if (!next.path("enabled").asBoolean()) {
                    synchronized (ctrl.lock) {
                        if (ctrl.scheduledFuture != null) ctrl.scheduledFuture.cancel(false);
                    }
                    Stats stat = stats.get(id);
                    if (stat != null) {
                        stat.lastProbe = 0;
                        stat.known = false;
                        stat.consecutiveFailures = 0;
                        stat.consecutiveSuccesses = 0;
                        stat.pendingState = null;
                    }
                } else {
                    MonitoringConfig oldEff = old != null ? computeEffectiveConfig(old.get("monitoring"), globalMonitoring) : null;
                    MonitoringConfig newEff = computeEffectiveConfig(next.get("monitoring"), globalMonitoring);
                    boolean policyChanged = oldEff == null
                            || oldEff.failureThreshold() != newEff.failureThreshold()
                            || oldEff.recoveryThreshold() != newEff.recoveryThreshold()
                            || oldEff.probeIntervalSeconds() != newEff.probeIntervalSeconds()
                            || oldEff.probeTimeoutMs() != newEff.probeTimeoutMs();

                    boolean identityOrPolicyChanged = (old == null)
                            || !old.path("enabled").asBoolean()
                            || policyChanged
                            || keyChanged
                            || !Objects.equals(old.path("healthUrl").asText(""), next.path("healthUrl").asText(""));

                    if (identityOrPolicyChanged) {
                        Stats stat = stats.get(id);
                        if (stat != null) {
                            stat.consecutiveFailures = 0;
                            stat.consecutiveSuccesses = 0;
                            stat.pendingState = null;
                        }
                    }
                    scheduleProbeLocked(id, ctrl, newEff.probeIntervalSeconds() * 1000L);
                }
            }

            notifyUpdate();
            ObjectNode res = next.deepCopy();
            enrichRoute(res);
            return res;
        }
    }

    public void delete(String id) {
        synchronized (routeLock) {
            route(id);

            ObjectNode closedIncident = null;
            long now = nowMs();
            synchronized (metricsLock) {
                for (ObjectNode inc : incidents) {
                    if (inc.path("backendId").asText().equals(id) && inc.path("endedAt").isNull()) {
                        closedIncident = inc.deepCopy();
                        closedIncident.put("endedAt", Instant.ofEpochMilli(now).toString());
                        long startMs = Instant.parse(inc.path("startedAt").asText()).toEpochMilli();
                        closedIncident.put("durationMs", Math.max(0, now - startMs));
                        closedIncident.put("endReason", "Backend removed");
                        break;
                    }
                }
            }

            // Persistir en SQLite de forma atómica con cancelación de pendientes y borrado de secreto
            try {
                storage.deleteRouteAtomicSync(id, closedIncident);
            } catch (Exception e) {
                LOG.error("Fallo al eliminar la ruta de SQLite: " + e.getClass().getSimpleName());
                throw new IllegalStateException("Error interno al eliminar la ruta del almacenamiento");
            }

            cachedApiKeys.remove(id);
            List<ObjectNode> updated = new ArrayList<>();
            for (ObjectNode r : routes) {
                if (!r.path("id").asText().equals(id)) {
                    updated.add(r.deepCopy());
                }
            }
            routes = updated;
            refreshRouteSnapshots();

            // Publicación e invalidación atómica tras commit exitoso
            synchronized (metricsLock) {
                if (closedIncident != null) {
                    for (ObjectNode inc : incidents) {
                        if (inc.path("id").asText().equals(closedIncident.path("id").asText())) {
                            inc.set("endedAt", closedIncident.get("endedAt"));
                            inc.set("durationMs", closedIncident.get("durationMs"));
                            inc.set("endReason", closedIncident.get("endReason"));
                            break;
                        }
                    }
                }
                BackendProbeControl ctrl = probeControls.remove(id);
                if (ctrl != null) {
                    ctrl.generation.incrementAndGet();
                    synchronized (ctrl.lock) {
                        if (ctrl.scheduledFuture != null) ctrl.scheduledFuture.cancel(false);
                    }
                }
                stats.remove(id);
            }
            notifyUpdate();
        }
    }

    public ObjectNode match(String value, boolean auth) {
        RouteSnapshot best = matchRoute(value, auth);
        return best != null ? best.route().deepCopy() : null;
    }

    public void audit(boolean auth, JsonNode request, String result, String backend, double ms) {
        checkDateRotation();
        synchronized (metricsLock) {
            ObjectNode entry = json.createObjectNode().put("timestamp", now().toString())
                    .put("type", auth ? "auth" : "acl").put("client", safe(request, "clientid"))
                    .put("topic", safe(request, "topic")).put("result", result).put("backend", backend).put("latencyMs", ms);
            if (logs.size() == 2000) logs.removeFirst();
            logs.addLast(entry);
        }
    }

    static String safe(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() ? node.path(field).asText() : "";
    }

    public ArrayNode logs() {
        synchronized (metricsLock) {
            ArrayNode result = json.createArrayNode();
            logs.descendingIterator().forEachRemaining(e -> result.add(e.deepCopy()));
            return result;
        }
    }

    public void clearLogs() {
        synchronized (metricsLock) {
            logs.clear();
        }
        saveDailyState();
        notifyUpdate();
    }

    public String stateSnapshotJson() {
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("routes", routes(false));
        snapshot.set("metrics", metrics());
        snapshot.set("incidents", incidents());
        snapshot.set("logs", logs());
        snapshot.set("settings", settings());
        if (hostMetrics != null) {
            snapshot.set("server", hostMetrics.snapshot());
        }
        return snapshot.toString();
    }

    public void notifyUpdate() {
        try { streamBus.onNext(stateSnapshotJson()); } catch (Exception ignored) {}
    }

    public Multi<String> streamEvents() {
        Multi<String> initial = Multi.createFrom().item(this::stateSnapshotJson);
        Multi<String> heartbeats = Multi.createFrom().ticks().every(Duration.ofSeconds(15)).map(tick -> "heartbeat");
        Multi<String> hostStream = Multi.createFrom().ticks().every(Duration.ofSeconds(2)).map(tick -> {
            try {
                ObjectNode n = json.createObjectNode();
                n.set("server", hostMetrics.snapshot());
                return n.toString();
            } catch (Exception e) {
                return "{}";
            }
        });
        return Multi.createBy().merging().streams(
            initial,
            streamBus,
            hostStream,
            heartbeats
        );
    }

    public void sample(String id, double ms) {
        checkDateRotation();
        synchronized (metricsLock) {
            Stats s = stats.computeIfAbsent(id, k -> new Stats());
            s.current = ms; s.count++; s.total += ms; s.min = Math.min(ms, s.min); s.max = Math.max(ms, s.max);
            if (s.samples.size() == 2000) s.samples.removeFirst();
            s.samples.add(ms);
        }
    }

    public void availability(String id, String reason, double probeMs, boolean probe) {
        if (!probe) return;
    }

    public ArrayNode incidents() {
        synchronized (metricsLock) {
            ArrayNode copy = json.createArrayNode();
            long now = nowMs();
            for (ObjectNode n : incidents) {
                ObjectNode item = n.deepCopy();
                if (item.path("endedAt").isNull()) {
                    long start = Instant.parse(item.path("startedAt").asText()).toEpochMilli();
                    item.put("durationMs", Math.max(0, now - start));
                }
                copy.add(item);
            }
            return copy;
        }
    }

    public ArrayNode metrics() {
        synchronized (metricsLock) {
            ArrayNode result = json.createArrayNode();
            for (ObjectNode r : routes) {
                String id = r.path("id").asText();
                Stats s = stats.get(id);
                ObjectNode m = result.addObject().put("id", id).put("name", r.path("name").asText());
                ArrayNode history = m.putArray("probeHistory");
                ArrayNode recentSamples = m.putArray("recentSamples");
                if (s != null) {
                    for (ProbeSample sample : s.probeHistory) {
                        history.addObject().put("time", sample.time()).put("online", sample.online())
                                .put("status", sample.status()).put("latencyMs", sample.latencyMs());
                    }
                    int skip = Math.max(0, s.samples.size() - 25);
                    int i = 0;
                    for (Double d : s.samples) if (i++ >= skip) recentSamples.add(d);
                }
                boolean enabled = r.path("enabled").asBoolean();
                boolean awaiting = (s == null || s.awaitingObservation);
                boolean known = (s != null && s.known);
                boolean online = (s != null && s.online);

                String status;
                if (!enabled) {
                    status = "Paused";
                } else if (awaiting || !known) {
                    status = "Unknown";
                } else if (!online) {
                    status = "Offline";
                } else if (s.current > 1000) {
                    status = "Slow";
                } else if (s.current >= 200) {
                    status = "Degraded";
                } else {
                    status = "Normal";
                }
                m.put("status", status);

                if (s != null) {
                    m.put("consecutiveFailures", s.consecutiveFailures);
                    m.put("consecutiveSuccesses", s.consecutiveSuccesses);
                    if (s.pendingState != null) m.put("pendingState", s.pendingState);
                    else m.putNull("pendingState");
                    m.put("awaitingObservation", s.awaitingObservation);

                    m.put("count", s.count).put("currentMs", s.current).put("probeMs", s.probeMs).put("probeHttpStatus", s.probeHttpStatus);
                    if (s.lastProbe > 0) m.put("lastProbe", Instant.ofEpochMilli(s.lastProbe).toString());
                    m.put("averageMs", s.count == 0 ? 0 : s.total / s.count);
                    m.put("minMs", s.count == 0 ? 0 : s.min).put("maxMs", s.max);
                    List<Double> sorted = s.samples.stream().sorted().toList();
                    m.put("p95Ms", sorted.isEmpty() ? 0 : sorted.get((int)Math.ceil(sorted.size() * .95) - 1));
                    m.put("sampleSize", sorted.size());
                    if (s.observed > 0) m.put("observedSince", Instant.ofEpochMilli(s.observed).toString());
                    if (s.upMs + s.downMs > 0) m.put("uptimePercent", 100.0 * s.upMs / (s.upMs + s.downMs));
                    else m.putNull("uptimePercent");
                } else {
                    m.put("consecutiveFailures", 0);
                    m.put("consecutiveSuccesses", 0);
                    m.putNull("pendingState");
                    m.put("awaitingObservation", true);
                    m.put("count", 0);
                    m.putNull("uptimePercent");
                }
            }
            return result;
        }
    }

    public ObjectNode probe(String id) {
        BackendProbeControl ctrl = probeControls.computeIfAbsent(id, k -> new BackendProbeControl());
        if (!ctrl.slot.compareAndSet(false, true)) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Probe slot occupied for backend: " + id)).build());
        }

        ObjectNode routeSnapshot;
        String apiKey;
        MonitoringConfig effConfig;
        synchronized (routeLock) {
            ObjectNode r = null;
            for (ObjectNode item : routes) {
                if (item.path("id").asText().equals(id)) {
                    r = item;
                    break;
                }
            }
            if (r == null) {
                ctrl.slot.set(false);
                throw new NotFoundException("Backend not found");
            }
            if (!r.path("enabled").asBoolean()) {
                ctrl.slot.set(false);
                throw new BadRequestException("Backend is paused");
            }
            routeSnapshot = r.deepCopy();
            apiKey = cachedApiKeys.get(id);
            effConfig = computeEffectiveConfig(r.get("monitoring"), globalMonitoring);
        }

        try {
            int timeoutMs = effConfig.probeTimeoutMs();
            String healthUrl = routeSnapshot.path("healthUrl").asText();

            long start = System.nanoTime();
            String reason = null;
            int code = 0;
            try {
                var builder = HttpRequest.newBuilder(URI.create(healthUrl))
                        .timeout(Duration.ofMillis(timeoutMs)).GET();
                if (apiKey != null && !apiKey.isBlank()) {
                    builder.header("X-EMQX-API-KEY", apiKey);
                }
                code = client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                if (code >= 500) reason = "HTTP " + code;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return json.createObjectNode().put("online", false).put("httpStatus", 0)
                            .put("latencyMs", 0.0).put("reason", "Probe interrupted");
                }
                reason = e instanceof HttpTimeoutException ? "Timeout" : "Connection failure";
            }
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            return json.createObjectNode().put("online", reason == null).put("httpStatus", code)
                    .put("latencyMs", ms).put("reason", reason == null ? "" : reason);
        } finally {
            ctrl.slot.set(false);
        }
    }

    private ObjectNode findEnabledRoute(String id) {
        for (ObjectNode r : routes) {
            if (r.path("id").asText().equals(id) && r.path("enabled").asBoolean()) {
                return r;
            }
        }
        return null;
    }

    private void scheduleProbeLocked(String id, BackendProbeControl ctrl, long delayMs) {
        synchronized (ctrl.lock) {
            if (ctrl.scheduledFuture != null && !ctrl.scheduledFuture.isDone()) {
                ctrl.scheduledFuture.cancel(false);
            }
            if (timer == null || timer.isShutdown()) return;
            try {
                ctrl.scheduledFuture = timer.schedule(() -> {
                    if (probeWorkerExecutor != null && !probeWorkerExecutor.isShutdown()) {
                        probeWorkerExecutor.submit(() -> runAutomaticProbe(id));
                    }
                }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {}
        }
    }

    public static String truncateCodePoints(String str, int maxCodePoints) {
        if (str == null) return "";
        int codePoints = str.codePointCount(0, str.length());
        if (codePoints <= maxCodePoints) {
            return str;
        }
        int endIndex = str.offsetByCodePoints(0, maxCodePoints);
        return str.substring(0, endIndex);
    }

    private void runAutomaticProbe(String id) {
        BackendProbeControl ctrl = probeControls.get(id);
        if (ctrl == null) return;

        if (!ctrl.slot.compareAndSet(false, true)) {
            scheduleProbeLocked(id, ctrl, 1000);
            return;
        }

        ObjectNode routeSnapshot;
        String apiKey;
        long capturedGen;
        MonitoringConfig effConfig;

        synchronized (routeLock) {
            ObjectNode r = findEnabledRoute(id);
            if (r == null) {
                ctrl.slot.set(false);
                return;
            }
            routeSnapshot = r.deepCopy();
            apiKey = cachedApiKeys.get(id);
            capturedGen = ctrl.generation.get();
            effConfig = computeEffectiveConfig(r.get("monitoring"), globalMonitoring);
        }

        String healthUrl = routeSnapshot.path("healthUrl").asText();
        int timeoutMs = effConfig.probeTimeoutMs();

        long start = System.nanoTime();
        String reason = null;
        int code = 0;
        try {
            var builder = HttpRequest.newBuilder(URI.create(healthUrl))
                    .timeout(Duration.ofMillis(timeoutMs)).GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("X-EMQX-API-KEY", apiKey);
            }
            code = client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code >= 500) reason = "HTTP " + code;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                reason = "Probe interrupted";
            } else if (e instanceof HttpTimeoutException) {
                reason = "Timeout";
            } else {
                reason = "Connection failure";
            }
        }

        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        long sampleNow = nowMs();

        try {
            applyProbeSampleTransactional(id, code, reason, latencyMs, sampleNow, capturedGen);
        } finally {
            try {
                synchronized (ctrl.lock) {
                    long currentGen = ctrl.generation.get();
                    if (currentGen == capturedGen && findEnabledRoute(id) != null) {
                        scheduleProbeLocked(id, ctrl, effConfig.probeIntervalSeconds() * 1000L);
                    }
                }
            } finally {
                ctrl.slot.set(false);
            }
        }
    }

    record ProbeTransitionPlan(
            boolean healthy,
            ObjectNode incidentToPersist,
            ObjectNode outboxItemToPersist,
            boolean isRecovery,
            boolean newOnlineState,
            boolean newKnownState,
            int newConsecutiveSuccesses,
            int newConsecutiveFailures,
            long newFirstSuccessTime,
            long newFirstFailureTime,
            String newPendingState,
            int code,
            String reason,
            double latencyMs
    ) {}

    void applyProbeSampleTransactional(String id, int code, String reason, double latencyMs, long sampleTimeEpochMs, Long capturedGen) {
        checkDateRotation();
        synchronized (routeLock) {
            ObjectNode route = findEnabledRoute(id);
            if (route == null) return;

            BackendProbeControl ctrl = probeControls.get(id);
            if (capturedGen != null && ctrl != null && ctrl.generation.get() != capturedGen) {
                return;
            }

            String backendName = route.path("name").asText("Backend");
            MonitoringConfig effMon = computeEffectiveConfig(route.get("monitoring"), globalMonitoring);
            TelegramConfig effTel = computeEffectiveTelegram(route.get("telegram"), globalTelegram);

            ProbeTransitionPlan plan;
            synchronized (metricsLock) {
                Stats s = stats.computeIfAbsent(id, k -> new Stats());
                plan = computeTransitionPlan(id, backendName, code, reason, latencyMs, effMon, effTel, sampleTimeEpochMs, s);
            }

            if (plan.incidentToPersist != null || plan.outboxItemToPersist != null) {
                try {
                    storage.saveIncidentWithOutboxSync(plan.incidentToPersist, plan.outboxItemToPersist);
                } catch (Exception e) {
                    LOG.error("Fallo al persistir transicion incidente+outbox en SQLite para backend " + id + "; abortando publicacion", e);
                    return;
                }
            }

            synchronized (metricsLock) {
                if (capturedGen != null && ctrl != null && ctrl.generation.get() != capturedGen) {
                    return;
                }
                Stats s = stats.computeIfAbsent(id, k -> new Stats());
                applyTransitionPlan(s, plan, sampleTimeEpochMs, effMon.probeIntervalSeconds());

                if (plan.incidentToPersist != null) {
                    if (plan.isRecovery) {
                        for (ObjectNode inc : incidents) {
                            if (inc.path("id").asText().equals(plan.incidentToPersist.path("id").asText())) {
                                inc.set("endedAt", plan.incidentToPersist.get("endedAt"));
                                inc.set("durationMs", plan.incidentToPersist.get("durationMs"));
                                inc.set("endReason", plan.incidentToPersist.get("endReason"));
                                break;
                            }
                        }
                    } else {
                        incidents.add(plan.incidentToPersist.deepCopy());
                    }
                }
            }

            notifyUpdate();
            if (plan.outboxItemToPersist != null) {
                wakeTelegramWorker();
            }
        }
    }

    private ProbeTransitionPlan computeTransitionPlan(
            String id,
            String backendName,
            int code,
            String reason,
            double latencyMs,
            MonitoringConfig effMon,
            TelegramConfig effTel,
            long now,
            Stats s
    ) {
        int failThreshold = effMon.failureThreshold();
        int recThreshold = effMon.recoveryThreshold();
        boolean healthy = (code > 0 && code < 500 && reason == null);

        ObjectNode openIncident = null;
        for (ObjectNode inc : incidents) {
            if (inc.path("backendId").asText().equals(id) && inc.path("endedAt").isNull()) {
                openIncident = inc;
                break;
            }
        }

        ObjectNode incidentToPersist = null;
        ObjectNode outboxItemToPersist = null;
        boolean isRecovery = false;

        boolean newOnlineState = s.online;
        boolean newKnownState = s.known;
        int newConsecutiveSuccesses = s.consecutiveSuccesses;
        int newConsecutiveFailures = s.consecutiveFailures;
        long newFirstSuccessTime = s.firstSuccessTime;
        long newFirstFailureTime = s.firstFailureTime;
        String newPendingState = s.pendingState;

        if (healthy) {
            newConsecutiveFailures = 0;
            newConsecutiveSuccesses = Math.min(s.consecutiveSuccesses + 1, 1000);
            if (newConsecutiveSuccesses == 1) {
                newFirstSuccessTime = now;
            }

            if (openIncident != null) {
                newPendingState = "recovery";
                newOnlineState = false; // Permanece confirmado Offline mientras se confirma recuperación
                newKnownState = true;   // Con incidente abierto, la primera observación mantiene confirmado Offline
                if (newConsecutiveSuccesses >= recThreshold) {
                    // Recuperación confirmada: fecha del PRIMER éxito de la racha
                    long endMs = newFirstSuccessTime;
                    isRecovery = true;
                    incidentToPersist = openIncident.deepCopy();
                    incidentToPersist.put("endedAt", Instant.ofEpochMilli(endMs).toString());
                    long startMs = Instant.parse(openIncident.path("startedAt").asText()).toEpochMilli();
                    long durationMs = Math.max(0, endMs - startMs);
                    incidentToPersist.put("durationMs", durationMs);
                    incidentToPersist.put("endReason", "Recovered");

                    if (globalTelegram.enabled() && effTel.enabled() && effTel.chatId() != null && !effTel.chatId().isBlank() && isTelegramTokenConfigured()) {
                        String incId = incidentToPersist.path("id").asText();
                        String msg = "[RECUPERADO] Backend '" + backendName + "' restablecido.\nDuración: " + durationMs + " ms\nFin: " + Instant.ofEpochMilli(endMs).toString();
                        outboxItemToPersist = createOutboxNode(UUID.randomUUID().toString(), id, incId, "incident.recovered", effTel.chatId(), msg, formatIso(now));
                    }

                    newOnlineState = true;
                    newKnownState = true;
                    newPendingState = null;
                    newConsecutiveSuccesses = 0;
                }
            } else {
                newOnlineState = true;
                newKnownState = true;
                newPendingState = null;
            }
        } else {
            String failReason = (reason != null && !reason.isBlank()) ? reason : (code >= 500 ? "HTTP " + code : "Connection failure");
            newConsecutiveSuccesses = 0;
            newConsecutiveFailures = Math.min(s.consecutiveFailures + 1, 1000);
            if (newConsecutiveFailures == 1) {
                newFirstFailureTime = now;
            }

            if (openIncident == null) {
                newPendingState = "failure";
                if (newConsecutiveFailures >= failThreshold) {
                    // Incidente confirmado: fecha del PRIMER fallo de la racha
                    long startMs = newFirstFailureTime;
                    incidentToPersist = json.createObjectNode();
                    incidentToPersist.put("id", UUID.randomUUID().toString());
                    incidentToPersist.put("backendId", id);
                    incidentToPersist.put("backend", backendName);
                    incidentToPersist.put("startedAt", Instant.ofEpochMilli(startMs).toString());
                    incidentToPersist.put("reason", failReason);
                    incidentToPersist.putNull("endedAt");
                    incidentToPersist.putNull("durationMs");
                    incidentToPersist.putNull("endReason");

                    if (globalTelegram.enabled() && effTel.enabled() && effTel.chatId() != null && !effTel.chatId().isBlank() && isTelegramTokenConfigured()) {
                        String incId = incidentToPersist.path("id").asText();
                        String msg = "[ALERTA] Backend '" + backendName + "' caído.\nMotivo: " + failReason + "\nHora: " + Instant.ofEpochMilli(startMs).toString();
                        outboxItemToPersist = createOutboxNode(UUID.randomUUID().toString(), id, incId, "incident.opened", effTel.chatId(), msg, formatIso(now));
                    }

                    newOnlineState = false;
                    newKnownState = true;
                    newPendingState = null;
                    newConsecutiveFailures = 0;
                } else {
                    // Aún no alcanza umbral de fallo y no hay incidente previo:
                    // NO confirmar known todavía si !s.known
                }
            } else {
                newOnlineState = false;
                newKnownState = true;
                newPendingState = null;
            }
        }

        return new ProbeTransitionPlan(
                healthy, incidentToPersist, outboxItemToPersist, isRecovery,
                newOnlineState, newKnownState, newConsecutiveSuccesses, newConsecutiveFailures,
                newFirstSuccessTime, newFirstFailureTime, newPendingState,
                code, reason, latencyMs
        );
    }

    private ObjectNode createOutboxNode(String id, String backendId, String incidentId, String type, String chatId, String message, String nowIso) {
        ObjectNode item = json.createObjectNode();
        item.put("id", id);
        if (backendId != null) item.put("backendId", backendId); else item.putNull("backendId");
        if (incidentId != null) item.put("incidentId", incidentId); else item.putNull("incidentId");
        item.put("type", type);
        item.put("chatId", chatId);
        item.put("message", truncateCodePoints(message, 4096));
        item.put("status", "pending");
        item.put("attempts", 0);
        item.put("createdAt", nowIso);
        item.put("nextAttemptAt", nowIso);
        item.putNull("lastAttemptAt");
        item.putNull("sentAt");
        item.putNull("lastError");
        return item;
    }

    private void applyTransitionPlan(Stats s, ProbeTransitionPlan plan, long now, int intervalSec) {
        boolean hadPreviousObservation = !s.awaitingObservation;
        long delta = now - s.lastProbe;
        if (hadPreviousObservation && delta > 0 && delta <= intervalSec * 2000L) {
            if (s.probeOnline) s.upMs += delta;
            else s.downMs += delta;
        }
        s.lastProbe = now;
        s.probeMs = plan.latencyMs;
        s.probeHttpStatus = plan.code;
        s.probeOnline = plan.healthy;
        if (!hadPreviousObservation) {
            s.observed = now;
        }

        if (s.probeHistory.size() >= 2880) s.probeHistory.removeFirst();
        s.probeHistory.addLast(new ProbeSample(Instant.ofEpochMilli(now).toString(), plan.healthy, plan.code, plan.latencyMs));

        s.awaitingObservation = false;

        s.online = plan.newOnlineState;
        s.known = plan.newKnownState;
        s.consecutiveSuccesses = plan.newConsecutiveSuccesses;
        s.consecutiveFailures = plan.newConsecutiveFailures;
        s.firstSuccessTime = plan.newFirstSuccessTime;
        s.firstFailureTime = plan.newFirstFailureTime;
        s.pendingState = plan.newPendingState;
    }

    public ObjectNode getNotificationHistory(int limit, int offset) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int boundedOffset = Math.max(0, offset);
        try {
            return storage.loadNotificationHistorySync(boundedLimit, boundedOffset);
        } catch (Exception e) {
            throw new IllegalStateException("Error al consultar historial de notificaciones: " + e.getMessage(), e);
        }
    }

    public ObjectNode createTestNotification(ObjectNode body) {
        synchronized (routeLock) {
            String backendId = null;
            if (body != null && body.hasNonNull("backendId")) {
                backendId = body.path("backendId").asText().trim();
                if (backendId.isEmpty()) backendId = null;
            }

            String targetChatId;
            String backendName = null;

            if (backendId != null) {
                ObjectNode r = findEnabledRoute(backendId);
                if (r == null) {
                    throw new BadRequestException("La ruta especificada no existe o se encuentra deshabilitada");
                }
                backendName = r.path("name").asText("Backend");
                TelegramConfig effTel = computeEffectiveTelegram(r.get("telegram"), globalTelegram);
                if (!globalTelegram.enabled() || !effTel.enabled() || effTel.chatId() == null || effTel.chatId().isBlank()) {
                    throw new BadRequestException("Telegram no está efectivamente habilitado para este backend");
                }
                targetChatId = effTel.chatId();
            } else {
                if (!globalTelegram.enabled() || globalTelegram.chatId() == null || globalTelegram.chatId().isBlank()) {
                    throw new BadRequestException("Telegram no está habilitado globalmente o no tiene un chatId configurado");
                }
                targetChatId = globalTelegram.chatId();
            }

            if (!isTelegramTokenConfigured()) {
                throw new BadRequestException("No se puede enviar prueba: no hay bot-token de Telegram configurado en el gateway");
            }

            String message = (backendName != null)
                    ? "[TEST] Notificación de prueba para backend '" + backendName + "'"
                    : "[TEST] Notificación de prueba global desde MQTT Gateway";
            message = truncateCodePoints(message, 4096);

            String id = UUID.randomUUID().toString();
            String nowIso = formatIso(nowMs());

            ObjectNode item = json.createObjectNode();
            item.put("id", id);
            if (backendId != null) item.put("backendId", backendId);
            else item.putNull("backendId");
            item.putNull("incidentId");
            item.put("type", "test");
            item.put("chatId", targetChatId);
            item.put("message", message);
            item.put("status", "pending");
            item.put("attempts", 0);
            item.put("createdAt", nowIso);
            item.put("nextAttemptAt", nowIso);
            item.putNull("lastAttemptAt");
            item.putNull("sentAt");
            item.putNull("lastError");

            String finalStatus;
            try {
                finalStatus = storage.saveOutboxItemSync(item);
            } catch (Exception e) {
                throw new IllegalStateException("Error al registrar notificación de prueba en SQLite: " + e.getMessage(), e);
            }

            wakeTelegramWorker();

            ObjectNode res = json.createObjectNode();
            res.put("id", id);
            res.put("status", finalStatus);
            return res;
        }
    }

    private void wakeTelegramWorker() {
        if (wakeScheduled.compareAndSet(false, true)) {
            if (telegramWorkerExecutor != null && !telegramWorkerExecutor.isShutdown()) {
                try {
                    telegramWorkerExecutor.submit(() -> {
                        wakeScheduled.set(false);
                        dispatchOutboxLoop();
                    });
                } catch (RejectedExecutionException e) {
                    wakeScheduled.set(false);
                }
            } else {
                wakeScheduled.set(false);
            }
        }
    }

    private void dispatchOutboxLoop() {
        try {
            long now = nowMs();
            if (now - lastMaintenanceEpochMs >= 60000L) {
                lastMaintenanceEpochMs = now;
                runOutboxMaintenance(now);
            }

            if (!globalTelegram.enabled() || !isTelegramTokenConfigured()) {
                return;
            }

            processOutboxBatch(50);
        } catch (Exception e) {
            LOG.warn("Error durante despacho de notificaciones outbox: " + e.getMessage());
        }
    }

    private void runOutboxMaintenance(long now) {
        try {
            String cutoff24h = formatIso(now - 86400000L);
            storage.expirePendingOutboxBatchSync(cutoff24h);

            String cutoff30d = formatIso(now - 30L * 86400000L);
            storage.purgeTerminalOutboxSync(cutoff30d);
        } catch (Exception e) {
            LOG.warn("Error durante mantenimiento periódico de outbox: " + e.getMessage());
        }
    }

    int triggerOutboxDispatchForTesting() {
        runOutboxMaintenance(nowMs());
        return processOutboxBatch(50);
    }

    ObjectNode getOutboxSummaryForTesting() {
        try {
            ObjectNode history = storage.loadNotificationHistorySync(1, 0);
            return (ObjectNode) history.get("summary");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int processOutboxBatch(int maxBatch) {
        if (!globalTelegram.enabled() || !isTelegramTokenConfigured()) {
            return 0;
        }

        String nowIso = formatIso(nowMs());
        List<ObjectNode> batch;
        try {
            batch = storage.loadPendingOutboxBatchSync(nowIso, maxBatch);
        } catch (Exception e) {
            LOG.warn("Error al cargar lote de outbox: " + e.getMessage());
            return 0;
        }

        if (batch.isEmpty()) return 0;

        int processed = 0;
        for (ObjectNode item : batch) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            long now = nowMs();

            synchronized (globalSendTimestamps) {
                while (!globalSendTimestamps.isEmpty() && now - globalSendTimestamps.peekFirst() >= 1000L) {
                    globalSendTimestamps.pollFirst();
                }
                if (globalSendTimestamps.size() >= 10) {
                    break;
                }
            }

            String id = item.path("id").asText();
            String chatId = item.path("chatId").asText();
            String message = item.path("message").asText();

            long lastAttempt = lastChatAttemptEpochMs.getOrDefault(chatId, 0L);
            if (now < lastAttempt + 3000L) {
                continue;
            }

            String claimTimeIso = formatIso(now);
            boolean claimed;
            try {
                claimed = storage.claimOutboxSendingSync(id, claimTimeIso);
            } catch (Exception e) {
                continue;
            }
            if (!claimed) continue;

            synchronized (globalSendTimestamps) {
                globalSendTimestamps.addLast(now);
            }
            lastChatAttemptEpochMs.put(chatId, now);
            try {
                storage.updateChatActivitySync(chatId, claimTimeIso);
            } catch (Exception ignored) {}

            int attemptsAfterClaim = item.path("attempts").asInt(0) + 1;
            processed++;
            dispatchTelegramMessage(id, chatId, message, attemptsAfterClaim);
        }
        return processed;
    }

    private void dispatchTelegramMessage(String outboxId, String chatId, String message, int currentAttempts) {
        boolean success = false;
        boolean permanentFailure = false;
        String category = null;
        int retryAfterSec = -1;

        try {
            String token = this.cachedTelegramBotToken;
            if (token == null || token.isBlank()) {
                return;
            }
            String baseUrl = getEffectiveTelegramApiUrl();
            String sendUrl = baseUrl.replaceAll("/+$", "") + "/bot" + token + "/sendMessage";

            ObjectNode payload = json.createObjectNode();
            payload.put("chat_id", chatId);
            payload.put("text", truncateCodePoints(message, 4096));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sendUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = getTelegramHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            int httpCode = resp.statusCode();
            String body = resp.body();

            JsonNode bodyNode = null;
            if (body != null && !body.isBlank()) {
                try {
                    bodyNode = json.readTree(body);
                } catch (Exception ignored) {}
            }

            if (httpCode == 429) {
                category = "telegram_http_429";
                if (bodyNode != null && bodyNode.has("parameters") && bodyNode.path("parameters").hasNonNull("retry_after")) {
                    retryAfterSec = bodyNode.path("parameters").path("retry_after").asInt(-1);
                }
            } else if (httpCode >= 400 && httpCode < 500) {
                category = "telegram_http_" + httpCode;
                permanentFailure = true;
            } else if (httpCode >= 500) {
                category = "telegram_http_5xx";
            } else if (httpCode == 200) {
                if (bodyNode != null && bodyNode.isObject()) {
                    boolean ok = bodyNode.path("ok").asBoolean(false);
                    if (ok) {
                        success = true;
                    } else {
                        int errCode = bodyNode.path("error_code").asInt(200);
                        if (bodyNode.has("parameters") && bodyNode.path("parameters").hasNonNull("retry_after")) {
                            retryAfterSec = bodyNode.path("parameters").path("retry_after").asInt(-1);
                        }
                        if (errCode == 429) {
                            category = "telegram_http_429";
                        } else if (errCode >= 400 && errCode < 500) {
                            category = "telegram_http_" + errCode;
                            permanentFailure = true;
                        } else if (errCode >= 500) {
                            category = "telegram_http_5xx";
                        } else {
                            category = "invalid_response";
                        }
                    }
                } else {
                    category = "invalid_response";
                }
            } else {
                category = "invalid_response";
            }
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                category = "connection_failure";
            } else if (t instanceof HttpTimeoutException) {
                category = "timeout";
            } else {
                category = "connection_failure";
            }
        }

        long afterCallMs = nowMs();
        String afterCallIso = formatIso(afterCallMs);

        try {
            if (success) {
                storage.completeOutboxSync(outboxId, "sent", null, afterCallIso, null);
            } else if (permanentFailure || currentAttempts >= 5) {
                storage.completeOutboxSync(outboxId, "failed", null, null, category);
            } else {
                long delayMs;
                if (retryAfterSec > 0) {
                    delayMs = Math.max(3000L, retryAfterSec * 1000L);
                } else {
                    long baseSec = computeBackoffSeconds(currentAttempts);
                    long jitter = ThreadLocalRandom.current().nextLong(1000L);
                    delayMs = Math.max(3000L, baseSec * 1000L + jitter);
                }
                String nextAttemptAt = formatIso(afterCallMs + delayMs);
                storage.completeOutboxSync(outboxId, "pending", nextAttemptAt, null, category);
            }
        } catch (Exception e) {
            LOG.warn("Error al completar outbox " + outboxId + ": " + e.getMessage());
        }
    }

    private static long computeBackoffSeconds(int attempts) {
        return switch (attempts) {
            case 1 -> 10L;
            case 2 -> 30L;
            case 3 -> 120L;
            default -> 300L;
        };
    }

    void recordProbeSample(String id, boolean online, int httpStatus, double latencyMs, Instant now, boolean manual) {
        String reason = (online && (httpStatus <= 0 || httpStatus < 500)) ? null : (httpStatus >= 500 ? "HTTP " + httpStatus : "Connection failure");
        recordProbeSample(id, online, httpStatus, reason, latencyMs, now, manual);
    }

    void recordProbeSample(String id, boolean online, int httpStatus, String reason, double latencyMs, Instant now, boolean manual) {
        if (manual) return;
        long timeMs = (now != null) ? now.toEpochMilli() : nowMs();
        applyProbeSampleTransactional(id, httpStatus, reason, latencyMs, timeMs, null);
    }

    void resetObservationForTesting(String id) {
        synchronized (metricsLock) {
            Stats s = stats.computeIfAbsent(id, k -> new Stats());
            s.awaitingObservation = true;
            s.known = false;
            s.consecutiveFailures = 0;
            s.consecutiveSuccesses = 0;
            s.firstFailureTime = 0;
            s.firstSuccessTime = 0;
            s.pendingState = null;
        }
    }

    Stats getStatsForTesting(String id) {
        synchronized (metricsLock) {
            return stats.computeIfAbsent(id, k -> new Stats());
        }
    }
}
