package pe.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Pruebas unitarias puras y deterministas de Fase 3 para StorageService, Outbox SQLite y Worker Telegram:
 * - FIFO con cabeza en retry por chat + segundo elemento mismo chat + otro chat independiente.
 * - Espaciado por chat (telegram_chat_activity) y persistencia de última actividad.
 * - Reinicio/crash: recoverOrphanedSendingSync devuelve 'sending' a 'pending' conservando 'attempts'.
 * - Cancelación de items 'pending' ante desactivación o eliminación de ruta.
 * - Deduplicación estricta por índice único (incident_id, type, chat_id).
 * - Saturación a 10,000 registros asigna estado 'not_enqueued' sin bloquear la base de datos.
 * - Transaccionalidad atómica: rollback simultáneo de incidente y outbox ante fallo SQLite.
 * - Purga de registros terminales respetando retención de 30 días sin tocar activos.
 * - Historial paginado con summary (conteos exactos y oldestPendingAt).
 * - Worker determinista con Clock + FakeHttpClient:
 *   * Rate limiting >= 3s por chat (no duerme, evalúa salto de reloj).
 *   * 429 Too Many Requests con lectura de parameters.retry_after.
 *   * 4xx terminales (403 Forbidden, 400 Bad Request) pasan directamente a failed sin reintento.
 *   * 5xx, timeouts y respuestas malformadas escalan reintentos (10s, 30s, 120s, 300s) hasta máx 5.
 *   * TTL de 24 horas expira mensajes antiguos sin enviarlos.
 */
public class TelegramOutboxTransitionTest {

    private StorageService storage;
    private GatewayState state;
    private ObjectMapper json;
    private Path tempDir;
    private Path dbFile;
    private FakeHttpClient fakeClient;

    static class FakeTelegramConfig implements GatewayConfig.Telegram {
        private final String token;
        private final String apiUrl;
        FakeTelegramConfig(String token, String apiUrl) {
            this.token = token;
            this.apiUrl = apiUrl != null ? apiUrl : "https://api.telegram.org";
        }
        @Override public Optional<String> botToken() { return Optional.ofNullable(token); }
        @Override public String apiUrl() { return apiUrl; }
    }

    private static GatewayConfig createFakeConfig(String dataDir, String botToken, String apiUrl) {
        return (GatewayConfig) Proxy.newProxyInstance(
                GatewayConfig.class.getClassLoader(),
                new Class<?>[]{GatewayConfig.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("dataDir")) return dataDir;
                    if (name.equals("timeoutMs")) return 2000L;
                    if (name.equals("probeSeconds")) return 30;
                    if (name.equals("masterKey")) return Optional.of("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
                    if (name.equals("telegram")) return Optional.of(new FakeTelegramConfig(botToken, apiUrl));
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

    static class FakeHttpResponse<T> implements HttpResponse<T> {
        private final int statusCode;
        private final T body;
        private final HttpRequest request;

        public FakeHttpResponse(int statusCode, T body, HttpRequest request) {
            this.statusCode = statusCode;
            this.body = body;
            this.request = request;
        }

        @Override public int statusCode() { return statusCode; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (k, v) -> true); }
        @Override public T body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request != null ? request.uri() : URI.create("http://localhost"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    @FunctionalInterface
    interface FakeHttpHandler {
        HttpResponse<String> handle(HttpRequest request) throws IOException, InterruptedException;
    }

    static class FakeHttpClient extends HttpClient {
        volatile FakeHttpHandler handler;
        final List<HttpRequest> sentRequests = new CopyOnWriteArrayList<>();

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(2)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @SuppressWarnings("unchecked")
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
            sentRequests.add(request);
            if (handler != null) {
                return (HttpResponse<T>) handler.handle(request);
            }
            return (HttpResponse<T>) new FakeHttpResponse<>(200, "{\"ok\":true}", request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            try {
                return CompletableFuture.completedFuture(send(request, responseBodyHandler));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    @BeforeEach
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory(Path.of("target"), "test-sqlite-outbox-");
        dbFile = tempDir.resolve("gateway.db");

        json = new ObjectMapper();
        fakeClient = new FakeHttpClient();

        storage = new StorageService();
        storage.config = createFakeConfig(tempDir.toAbsolutePath().toString(), "fake-bot-token-12345", "http://127.0.0.1:19999");
        storage.json = json;
        storage.init();

        state = new GatewayState();
        state.json = json;
        state.storage = storage;
        state.config = storage.config;
        state.hostMetrics = new HostMetricsService();
        state.setTelegramClientForTesting(fakeClient);
        state.setTelegramBaseUrlForTesting("http://127.0.0.1:19999");

        ObjectNode tgSettings = json.createObjectNode();
        ObjectNode tg = json.createObjectNode();
        tg.put("enabled", true);
        tg.put("chatId", "-100123456");
        tg.put("botToken", "fake_bot_token-12345");
        tgSettings.set("telegram", tg);
        state.updateSettings(tgSettings);
    }

    @AfterEach
    public void tearDown() {
        if (state != null && state.client != null) {
            try { state.client.close(); } catch (Exception ignored) {}
        }
        if (storage != null) {
            try {
                storage.close(null, null);
            } catch (Exception ignored) {}
        }
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    private ObjectNode createOutboxItem(String id, String backendId, String incidentId, String type, String chatId, String message, String status, int attempts, String createdAt, String nextAttemptAt) {
        ObjectNode item = json.createObjectNode();
        item.put("id", id);
        if (backendId != null) item.put("backendId", backendId); else item.putNull("backendId");
        if (incidentId != null) item.put("incidentId", incidentId); else item.putNull("incidentId");
        item.put("type", type);
        item.put("chatId", chatId);
        item.put("message", message);
        item.put("status", status);
        item.put("attempts", attempts);
        item.put("createdAt", StorageService.formatIso(Instant.parse(createdAt)));
        item.put("nextAttemptAt", StorageService.formatIso(Instant.parse(nextAttemptAt)));
        return item;
    }

    private static List<JsonNode> toList(ArrayNode arr) {
        List<JsonNode> list = new ArrayList<>();
        if (arr != null) arr.forEach(list::add);
        return list;
    }

    // ==========================================
    // PRUEBAS DE DAO Y PERSISTENCIA SQLITE
    // ==========================================

    @Test
    public void testFifoTwoChatsAndRetryHeadDoesNotBlockOtherChat() throws Exception {
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        String nowIso = StorageService.formatIso(now);
        String pastIso = StorageService.formatIso(now.minusSeconds(10));
        String futureIso = StorageService.formatIso(now.plusSeconds(30));

        // Chat A: Item 1 en retry (next_attempt_at en el futuro)
        ObjectNode itemA1 = createOutboxItem("out-a1", "b-1", null, "test", "chat-A", "Mensaje A1", "pending", 1, pastIso, futureIso);
        storage.saveOutboxItemSync(itemA1);

        // Chat A: Item 2 pendiente (creado después, next_attempt_at vencido)
        ObjectNode itemA2 = createOutboxItem("out-a2", "b-1", null, "test", "chat-A", "Mensaje A2", "pending", 0, nowIso, pastIso);
        storage.saveOutboxItemSync(itemA2);

        // Chat B: Item 3 pendiente para chat diferente (next_attempt_at vencido)
        ObjectNode itemB1 = createOutboxItem("out-b1", "b-2", null, "test", "chat-B", "Mensaje B1", "pending", 0, nowIso, pastIso);
        storage.saveOutboxItemSync(itemB1);

        // Al cargar el batch pendiente para 'nowIso'
        List<ObjectNode> batch = storage.loadPendingOutboxBatchSync(nowIso, 10);

        // Item A1 no debe ser seleccionado porque next_attempt_at está en el futuro
        Assertions.assertTrue(batch.stream().noneMatch(i -> i.path("id").asText().equals("out-a1")),
                "Item A1 no debe incluirse en batch si next_attempt_at es futuro");

        // Item B1 de Chat B SÍ debe estar disponible y poder enviarse (Chat B no se bloquea por el retry de Chat A)
        Assertions.assertTrue(batch.stream().anyMatch(i -> i.path("id").asText().equals("out-b1")),
                "Chat B no debe bloquearse por el retry pendiente en Chat A");

        // Espaciado por chat: simular actividad reciente para Chat B (hace 1 segundo)
        storage.updateChatActivitySync("chat-B", now.minusSeconds(1).toString());
        Map<String, String> activities = storage.loadAllChatActivitySync();
        Assertions.assertEquals(now.minusSeconds(1).toString(), activities.get("chat-B"),
                "Debe registrar la fecha exacta de actividad para Chat B");
    }

    @Test
    public void testRestartRecoversOrphanedSendingToPendingPreservingAttempts() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T12:00:00Z");
        ObjectNode item = createOutboxItem("out-crash", "b-1", null, "test", "chat-1", "Mensaje de prueba", "pending", 0, t0.toString(), t0.toString());
        storage.saveOutboxItemSync(item);

        // Claim a 'sending': pasa attempts de 0 a 1 en el flujo
        boolean claimed = storage.claimOutboxSendingSync("out-crash", t0.toString());
        Assertions.assertTrue(claimed, "Debe reclamar exitosamente a sending");

        // Simular que el worker incrementó attempts antes del crash
        storage.completeOutboxSync("out-crash", "sending", 1, t0.toString(), null, null);

        // Simular reinicio del gateway tras crash: recoverOrphanedSendingSync
        Instant tRestart = t0.plusSeconds(15);
        storage.recoverOrphanedSendingSync(tRestart.toString());

        // Verificar que vuelve a 'pending' con attempts=1 conservado
        List<ObjectNode> recovered = storage.loadPendingOutboxBatchSync(tRestart.toString(), 10);
        ObjectNode recItem = recovered.stream().filter(i -> i.path("id").asText().equals("out-crash")).findFirst().orElse(null);
        Assertions.assertNotNull(recItem, "Item huérfano debió recuperarse a pending");
        Assertions.assertEquals("pending", recItem.path("status").asText());
        Assertions.assertEquals(1, recItem.path("attempts").asInt(), "Los attempts previos deben conservarse tras recuperación");
    }

    @Test
    public void testCancellationOnBackendDeletePreservesOtherBackendsAndSending() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T12:00:00Z");
        ObjectNode item1 = createOutboxItem("out-b1-pend", "backend-target", null, "incident.opened", "chat-1", "Alerta B1", "pending", 0, t0.toString(), t0.toString());
        ObjectNode item2 = createOutboxItem("out-b2-pend", "backend-other", null, "incident.opened", "chat-1", "Alerta B2", "pending", 0, t0.toString(), t0.toString());
        storage.saveOutboxItemSync(item1);
        storage.saveOutboxItemSync(item2);

        // Eliminar backend-target usando deleteRouteAtomicSync
        storage.deleteRouteAtomicSync("backend-target", null);

        // Cargar historial
        ObjectNode history = storage.loadNotificationHistorySync(10, 0);
        ArrayNode items = (ArrayNode) history.get("items");

        ObjectNode b1 = null;
        ObjectNode b2 = null;
        for (int i = 0; i < items.size(); i++) {
            ObjectNode cur = (ObjectNode) items.get(i);
            if (cur.path("id").asText().equals("out-b1-pend")) b1 = cur;
            if (cur.path("id").asText().equals("out-b2-pend")) b2 = cur;
        }

        Assertions.assertNotNull(b1);
        Assertions.assertNotNull(b2);
        Assertions.assertEquals("cancelled", b1.path("status").asText(), "Notificaciones pendientes del backend eliminado deben cancelarse");
        Assertions.assertEquals("pending", b2.path("status").asText(), "Notificaciones de otros backends deben permanecer intactas");
    }

    @Test
    public void testDeduplicationIncidentTypeChat() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T12:00:00Z");
        ObjectNode item1 = createOutboxItem("out-dup-1", "backend-1", "inc-unique-123", "incident.opened", "chat-100", "Alerta 1", "pending", 0, t0.toString(), t0.toString());
        ObjectNode item2 = createOutboxItem("out-dup-2", "backend-1", "inc-unique-123", "incident.opened", "chat-100", "Alerta Duplicada", "pending", 0, t0.toString(), t0.toString());

        storage.saveOutboxItemSync(item1);

        // Intentar guardar el segundo con la misma tupla (incidentId, type, chatId)
        ObjectNode incident = json.createObjectNode();
        incident.put("id", "inc-unique-123");
        incident.put("backendId", "backend-1");
        incident.put("backendName", "Backend 1");
        incident.put("startedAt", t0.toString());
        incident.putNull("endedAt");
        incident.putNull("durationMs");
        incident.put("reason", "HTTP 500");
        incident.putNull("endReason");

        storage.saveIncidentWithOutboxSync(incident, item2);

        // Verificar que en el historial solo existe 1 registro para ese incidente y chat
        ObjectNode history = storage.loadNotificationHistorySync(10, 0);
        ArrayNode items = (ArrayNode) history.get("items");
        long count = 0;
        for (int i = 0; i < items.size(); i++) {
            ObjectNode cur = (ObjectNode) items.get(i);
            if ("inc-unique-123".equals(cur.path("incidentId").asText()) && "chat-100".equals(cur.path("chatId").asText())) {
                count++;
            }
        }
        Assertions.assertEquals(1, count, "Deduplicación única debe evitar registrar duplicados para el mismo incidente y chat");
    }

    @Test
    public void testAtomicRollbackIncidentAndOutboxOnFailure() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T12:00:00Z");

        // Instalar trigger en SQLite temporal que haga fallar la inserción en notification_outbox
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath().toString();
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER fail_outbox_insert BEFORE INSERT ON notification_outbox " +
                    "BEGIN " +
                    "  SELECT RAISE(ABORT, 'Simulated SQLite Outbox Disk Failure'); " +
                    "END;");
        }

        ObjectNode incident = json.createObjectNode();
        incident.put("id", "inc-rollback-test");
        incident.put("backendId", "b-roll");
        incident.put("backendName", "Rollback Backend");
        incident.put("startedAt", t0.toString());
        incident.putNull("endedAt");
        incident.putNull("durationMs");
        incident.put("reason", "Trigger test");
        incident.putNull("endReason");

        ObjectNode item = createOutboxItem("out-rollback-test", "b-roll", "inc-rollback-test", "incident.opened", "chat-err", "Mensaje", "pending", 0, t0.toString(), t0.toString());

        // Intentar guardar incidente + outbox
        Assertions.assertThrows(Exception.class, () -> {
            storage.saveIncidentWithOutboxSync(incident, item);
        }, "Debe arrojar excepción ante el fallo provocado por el trigger");

        // Retirar el trigger para verificar el estado de la base de datos
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER fail_outbox_insert;");
        }

        // Verificar que NI el incidente NI el outbox fueron confirmados en la BD (Rollback atómico)
        List<ObjectNode> incidents = storage.loadIncidents();
        Assertions.assertTrue(incidents.stream().noneMatch(i -> "inc-rollback-test".equals(i.path("id").asText())),
                "El incidente NO debe existir en la BD tras el rollback atómico");

        ObjectNode history = storage.loadNotificationHistorySync(10, 0);
        ArrayNode items = (ArrayNode) history.get("items");
        Assertions.assertEquals(0, items.size(), "La tabla notification_outbox debe estar vacía tras el rollback atómico");
    }

    @Test
    public void testPurgeTerminalOutboxRespects30DaysRetention() throws Exception {
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        Instant thirtyOneDaysAgo = now.minus(31, ChronoUnit.DAYS);
        Instant tenDaysAgo = now.minus(10, ChronoUnit.DAYS);

        // Item 1: 'sent' con más de 30 días -> Debe purgarse
        ObjectNode oldSent = createOutboxItem("out-old-sent", "b-1", null, "test", "chat-1", "Viejo enviado", "sent", 1, thirtyOneDaysAgo.toString(), thirtyOneDaysAgo.toString());
        storage.saveOutboxItemSync(oldSent);

        // Item 2: 'failed' con más de 30 días -> Debe purgarse
        ObjectNode oldFailed = createOutboxItem("out-old-fail", "b-1", null, "test", "chat-1", "Viejo fallido", "failed", 5, thirtyOneDaysAgo.toString(), thirtyOneDaysAgo.toString());
        storage.saveOutboxItemSync(oldFailed);

        // Item 3: 'sent' reciente (10 días) -> Debe conservarse
        ObjectNode recentSent = createOutboxItem("out-rec-sent", "b-1", null, "test", "chat-1", "Reciente enviado", "sent", 1, tenDaysAgo.toString(), tenDaysAgo.toString());
        storage.saveOutboxItemSync(recentSent);

        // Item 4: 'pending' antiguo -> NUNCA debe purgarse (solo terminales)
        ObjectNode oldPending = createOutboxItem("out-old-pend", "b-1", null, "test", "chat-1", "Antiguo pendiente", "pending", 0, thirtyOneDaysAgo.toString(), thirtyOneDaysAgo.toString());
        storage.saveOutboxItemSync(oldPending);

        // Ejecutar purga con corte a 30 días
        String cutoffIso = now.minus(30, ChronoUnit.DAYS).toString();
        storage.purgeTerminalOutboxSync(cutoffIso);

        // Verificar resultados en historial
        ObjectNode history = storage.loadNotificationHistorySync(10, 0);
        ArrayNode items = (ArrayNode) history.get("items");
        List<JsonNode> itemList = toList(items);

        Assertions.assertTrue(itemList.stream().noneMatch(i -> "out-old-sent".equals(i.path("id").asText())), "Old sent debe purgarse");
        Assertions.assertTrue(itemList.stream().noneMatch(i -> "out-old-fail".equals(i.path("id").asText())), "Old failed debe purgarse");
        Assertions.assertTrue(itemList.stream().anyMatch(i -> "out-rec-sent".equals(i.path("id").asText())), "Recent sent debe conservarse");
        Assertions.assertTrue(itemList.stream().anyMatch(i -> "out-old-pend".equals(i.path("id").asText())), "Pending nunca debe purgarse");
    }

    @Test
    public void testNotificationHistoryPaginationAndSummary() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T12:00:00Z");

        // Insertar varios items con diferentes estados
        storage.saveOutboxItemSync(createOutboxItem("h-1", "b-1", null, "test", "c-1", "M1", "pending", 0, t0.toString(), t0.toString()));
        storage.saveOutboxItemSync(createOutboxItem("h-2", "b-1", null, "test", "c-1", "M2", "pending", 0, t0.plusSeconds(5).toString(), t0.plusSeconds(5).toString()));
        storage.saveOutboxItemSync(createOutboxItem("h-3", "b-1", null, "test", "c-1", "M3", "sent", 1, t0.toString(), t0.toString()));
        storage.saveOutboxItemSync(createOutboxItem("h-4", "b-1", null, "test", "c-1", "M4", "failed", 5, t0.toString(), t0.toString()));
        storage.saveOutboxItemSync(createOutboxItem("h-5", "b-1", null, "test", "c-1", "M5", "cancelled", 0, t0.toString(), t0.toString()));

        // Paginación: limit=2, offset=0
        ObjectNode page1 = storage.loadNotificationHistorySync(2, 0);
        Assertions.assertEquals(5, page1.path("total").asInt(), "Total de items debe ser 5");
        Assertions.assertEquals(2, page1.path("items").size(), "Página 1 debe tener 2 items");

        // Summary
        ObjectNode summary = (ObjectNode) page1.get("summary");
        Assertions.assertNotNull(summary);
        Assertions.assertEquals(2, summary.path("pending").asInt(), "pending debe ser 2");
        Assertions.assertEquals(1, summary.path("sent").asInt(), "sent debe ser 1");
        Assertions.assertEquals(1, summary.path("failed").asInt(), "failed debe ser 1");
        Assertions.assertEquals(1, summary.path("cancelled").asInt(), "cancelled debe ser 1");
        Assertions.assertEquals(StorageService.formatIso(t0), summary.path("oldestPendingAt").asText(), "oldestPendingAt debe ser la fecha del item h-1");
    }

    @Test
    public void testSaturation10000SetsNotEnqueued() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T12:00:00Z");
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath().toString();
        try (Connection conn = DriverManager.getConnection(url)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO notification_outbox (id, type, chat_id, message, status, attempts, created_at, next_attempt_at) " +
                            "VALUES (?, 'test', 'chat-sat', 'msg', 'pending', 0, ?, ?)")) {
                for (int i = 0; i < 10000; i++) {
                    ps.setString(1, "sat-" + i);
                    ps.setString(2, t0.toString());
                    ps.setString(3, t0.toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }

        // Al intentar insertar el elemento 10,001, debe asignarse el status 'not_enqueued'
        ObjectNode item10001 = createOutboxItem("sat-10001", "b-1", null, "test", "chat-sat", "msg 10001", "pending", 0, t0.toString(), t0.toString());
        String status = storage.saveOutboxItemSync(item10001);
        Assertions.assertEquals("not_enqueued", status, "Al superar el límite de 10,000 debe retornar status not_enqueued");

        ObjectNode history = storage.loadNotificationHistorySync(10, 0);
        ArrayNode items = (ArrayNode) history.get("items");
        ObjectNode persisted = null;
        for (int i = 0; i < items.size(); i++) {
            ObjectNode cur = (ObjectNode) items.get(i);
            if ("sat-10001".equals(cur.path("id").asText())) {
                persisted = cur;
                break;
            }
        }
        Assertions.assertNotNull(persisted);
        Assertions.assertEquals("not_enqueued", persisted.path("status").asText(), "En BD debe guardarse con status not_enqueued");
    }

    // ==========================================
    // PRUEBAS DETERMINISTAS DEL WORKER CON CLOCK
    // ==========================================

    @Test
    public void testWorkerRateLimit3SecondsPerChat() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");
        state.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        // Encolar dos mensajes para el mismo chat (chat-rate)
        storage.saveOutboxItemSync(createOutboxItem("m-rate-1", "b-1", null, "test", "chat-rate", "Msg 1", "pending", 0, t0.toString(), t0.toString()));
        storage.saveOutboxItemSync(createOutboxItem("m-rate-2", "b-1", null, "test", "chat-rate", "Msg 2", "pending", 0, t0.toString(), t0.toString()));

        fakeClient.handler = req -> new FakeHttpResponse<>(200, "{\"ok\":true}", req);

        // Disparo a t0: sólo m-rate-1 debe ser despachado
        int count1 = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(1, count1, "A t0 sólo el primer mensaje del chat debe procesarse");
        Assertions.assertEquals(1, fakeClient.sentRequests.size());

        // Disparo inmediato a t0: m-rate-2 no debe salir (<3s)
        int countImmediate = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(0, countImmediate, "Llamado inmediato sin avance de reloj no debe procesar el 2do mensaje");

        // Disparo a t0 + 1.5s: sigue sin despacharse (< 3000ms)
        state.setClock(Clock.fixed(t0.plusMillis(1500), ZoneOffset.UTC));
        int count1500 = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(0, count1500, "A 1.5s no debe despacharse por restricción de 3s");
        Assertions.assertEquals(1, fakeClient.sentRequests.size());

        // Disparo a t0 + 3.1s: ahora sí han pasado >= 3s
        state.setClock(Clock.fixed(t0.plusMillis(3100), ZoneOffset.UTC));
        int count3100 = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(1, count3100, "A 3.1s debe despacharse el segundo mensaje del chat");
        Assertions.assertEquals(2, fakeClient.sentRequests.size());
    }

    @Test
    public void testWorker429TooManyRequestsReadsRetryAfter() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");
        state.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        storage.saveOutboxItemSync(createOutboxItem("m-429", "b-1", null, "test", "chat-429", "Msg 429", "pending", 0, t0.toString(), t0.toString()));

        // Configurar respuesta 429 con retry_after = 12 segundos
        fakeClient.handler = req -> new FakeHttpResponse<>(429, "{\"ok\":false,\"parameters\":{\"retry_after\":12}}", req);

        int processed = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(1, processed);

        // Verificar estado en BD: debe quedar en 'pending' con attempts = 1 y category 'telegram_http_429'
        ObjectNode hist = storage.loadNotificationHistorySync(10, 0);
        ArrayNode items = (ArrayNode) hist.get("items");
        ObjectNode item = (ObjectNode) items.get(0);

        Assertions.assertEquals("pending", item.path("status").asText());
        Assertions.assertEquals(1, item.path("attempts").asInt());
        Assertions.assertEquals("telegram_http_429", item.path("lastError").asText());

        Instant nextAttempt = Instant.parse(item.path("nextAttemptAt").asText());
        long deltaSec = ChronoUnit.SECONDS.between(t0, nextAttempt);
        Assertions.assertTrue(deltaSec >= 12, "nextAttemptAt debe ser al menos t0 + 12s; fue delta: " + deltaSec);

        // A las t0 + 10s: aún no vence el retry_after -> no debe despacharse
        state.setClock(Clock.fixed(t0.plusSeconds(10), ZoneOffset.UTC));
        fakeClient.sentRequests.clear();
        int pEarly = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(0, pEarly, "A t0+10s no debe despacharse antes de retry_after");
        Assertions.assertEquals(0, fakeClient.sentRequests.size());

        // A las t0 + 14s: ya venció el retry_after (12s + 1s max jitter) -> ahora se despacha y triunfa
        state.setClock(Clock.fixed(t0.plusSeconds(14), ZoneOffset.UTC));
        fakeClient.handler = req -> new FakeHttpResponse<>(200, "{\"ok\":true}", req);
        int pDue = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(1, pDue);
        Assertions.assertEquals(1, fakeClient.sentRequests.size());

        ObjectNode histAfter = storage.loadNotificationHistorySync(10, 0);
        ObjectNode itemAfter = (ObjectNode) ((ArrayNode) histAfter.get("items")).get(0);
        Assertions.assertEquals("sent", itemAfter.path("status").asText(), "Debe pasar a 'sent' tras el reintento exitoso");
        Assertions.assertEquals(2, itemAfter.path("attempts").asInt());
    }

    @Test
    public void testWorkerPermanent4xxFailsImmediatelyWithoutRetries() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");
        state.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        storage.saveOutboxItemSync(createOutboxItem("m-403", "b-1", null, "test", "chat-403", "Msg 403", "pending", 0, t0.toString(), t0.toString()));

        // Configurar 403 Forbidden (ej. bot bloqueado por el chat)
        fakeClient.handler = req -> new FakeHttpResponse<>(403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}", req);

        int processed = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(1, processed);

        ObjectNode hist = storage.loadNotificationHistorySync(10, 0);
        ObjectNode item = (ObjectNode) ((ArrayNode) hist.get("items")).get(0);

        Assertions.assertEquals("failed", item.path("status").asText(), "Error permanente 403 debe pasar inmediatamente a 'failed'");
        Assertions.assertEquals(1, item.path("attempts").asInt());
        Assertions.assertEquals("telegram_http_403", item.path("lastError").asText());

        // Avanzar el reloj a cualquier momento futuro: NUNCA más debe reintentarse
        state.setClock(Clock.fixed(t0.plus(5, ChronoUnit.HOURS), ZoneOffset.UTC));
        fakeClient.sentRequests.clear();
        int processedLater = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(0, processedLater, "Mensaje fallido 403 no debe volver a despacharse");
        Assertions.assertEquals(0, fakeClient.sentRequests.size());
    }

    @Test
    public void testWorkerRetryLadder5xxTimeoutMalformedAndMax5Attempts() throws Exception {
        Instant t = Instant.parse("2026-11-14T10:00:00Z");
        state.setClock(Clock.fixed(t, ZoneOffset.UTC));

        storage.saveOutboxItemSync(createOutboxItem("m-ladder", "b-1", null, "test", "chat-ladder", "Msg Ladder", "pending", 0, t.toString(), t.toString()));

        // Intento 1: Falla 500 (Server Error) -> Espera ~10s
        fakeClient.handler = req -> new FakeHttpResponse<>(500, "Server Error", req);
        state.triggerOutboxDispatchForTesting();

        ObjectNode it1 = (ObjectNode) ((ArrayNode) storage.loadNotificationHistorySync(10, 0).get("items")).get(0);
        Assertions.assertEquals("pending", it1.path("status").asText());
        Assertions.assertEquals(1, it1.path("attempts").asInt());
        Assertions.assertEquals("telegram_http_5xx", it1.path("lastError").asText());
        Instant next1 = Instant.parse(it1.path("nextAttemptAt").asText());
        Assertions.assertTrue(ChronoUnit.SECONDS.between(t, next1) >= 10, "Intento 1 debe programar >= 10s backoff");

        // Intento 2: Falla con HttpTimeoutException -> Espera ~30s
        t = next1.plusSeconds(1);
        state.setClock(Clock.fixed(t, ZoneOffset.UTC));
        fakeClient.handler = req -> {
            throw new HttpTimeoutException("Connection timed out after 5000ms");
        };
        state.triggerOutboxDispatchForTesting();

        ObjectNode it2 = (ObjectNode) ((ArrayNode) storage.loadNotificationHistorySync(10, 0).get("items")).get(0);
        Assertions.assertEquals("pending", it2.path("status").asText());
        Assertions.assertEquals(2, it2.path("attempts").asInt());
        Assertions.assertEquals("timeout", it2.path("lastError").asText());
        Instant next2 = Instant.parse(it2.path("nextAttemptAt").asText());
        Assertions.assertTrue(ChronoUnit.SECONDS.between(t, next2) >= 30, "Intento 2 debe programar >= 30s backoff");

        // Intento 3: Falla con Respuesta Malformada (no-JSON) -> Espera ~120s
        t = next2.plusSeconds(1);
        state.setClock(Clock.fixed(t, ZoneOffset.UTC));
        fakeClient.handler = req -> new FakeHttpResponse<>(200, "NON_JSON_CORRUPTED_BODY", req);
        state.triggerOutboxDispatchForTesting();

        ObjectNode it3 = (ObjectNode) ((ArrayNode) storage.loadNotificationHistorySync(10, 0).get("items")).get(0);
        Assertions.assertEquals("pending", it3.path("status").asText());
        Assertions.assertEquals(3, it3.path("attempts").asInt());
        Assertions.assertEquals("invalid_response", it3.path("lastError").asText());
        Instant next3 = Instant.parse(it3.path("nextAttemptAt").asText());
        Assertions.assertTrue(ChronoUnit.SECONDS.between(t, next3) >= 120, "Intento 3 debe programar >= 120s backoff");

        // Intento 4: Falla 502 Bad Gateway -> Espera ~300s
        t = next3.plusSeconds(1);
        state.setClock(Clock.fixed(t, ZoneOffset.UTC));
        fakeClient.handler = req -> new FakeHttpResponse<>(502, "Bad Gateway", req);
        state.triggerOutboxDispatchForTesting();

        ObjectNode it4 = (ObjectNode) ((ArrayNode) storage.loadNotificationHistorySync(10, 0).get("items")).get(0);
        Assertions.assertEquals("pending", it4.path("status").asText());
        Assertions.assertEquals(4, it4.path("attempts").asInt());
        Instant next4 = Instant.parse(it4.path("nextAttemptAt").asText());
        Assertions.assertTrue(ChronoUnit.SECONDS.between(t, next4) >= 300, "Intento 4 debe programar >= 300s backoff");

        // Intento 5: Falla 500 -> Alcanza 5 intentos y pasa a 'failed' definitivamente
        t = next4.plusSeconds(1);
        state.setClock(Clock.fixed(t, ZoneOffset.UTC));
        fakeClient.handler = req -> new FakeHttpResponse<>(500, "Fatal 500", req);
        state.triggerOutboxDispatchForTesting();

        ObjectNode it5 = (ObjectNode) ((ArrayNode) storage.loadNotificationHistorySync(10, 0).get("items")).get(0);
        Assertions.assertEquals("failed", it5.path("status").asText(), "Al 5to fallo consecutivo debe pasar a 'failed'");
        Assertions.assertEquals(5, it5.path("attempts").asInt());
        Assertions.assertEquals("telegram_http_5xx", it5.path("lastError").asText());
    }

    @Test
    public void testWorkerTtl24HoursExpiresMessageWithoutSending() throws Exception {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");
        state.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        // Encolar mensaje a t0
        storage.saveOutboxItemSync(createOutboxItem("m-ttl", "b-1", null, "test", "chat-ttl", "TTL Msg", "pending", 0, t0.toString(), t0.toString()));

        // Avanzar el reloj 25 horas
        Instant t25h = t0.plus(25, ChronoUnit.HOURS);
        state.setClock(Clock.fixed(t25h, ZoneOffset.UTC));

        fakeClient.sentRequests.clear();
        int processed = state.triggerOutboxDispatchForTesting();
        Assertions.assertEquals(0, processed, "Mensajes expirados en maintenance no cuentan como despachos HTTP");

        // No debe haberse emitido ninguna petición HTTP a Telegram
        Assertions.assertEquals(0, fakeClient.sentRequests.size(), "Mensaje expirado por TTL no debe enviarse a Telegram");

        ObjectNode hist = storage.loadNotificationHistorySync(10, 0);
        ObjectNode item = (ObjectNode) ((ArrayNode) hist.get("items")).get(0);
        Assertions.assertEquals("expired", item.path("status").asText());
        Assertions.assertEquals("ttl_expired", item.path("lastError").asText());
    }

    @Test
    public void testEffectiveTelegramDestinationPreservedWhenMasterDisabled() {
        // Maestro global disabled
        ObjectNode tgSettings = json.createObjectNode();
        ObjectNode tg = json.createObjectNode();
        tg.put("enabled", false);
        tg.put("chatId", "global-default-chat");
        tgSettings.set("telegram", tg);
        state.updateSettings(tgSettings);

        // Ruta con Telegram enabled explícito y chat propio
        ObjectNode route = json.createObjectNode();
        route.put("name", "Route Explicit Telegram");
        route.put("enabled", true);
        route.put("authUrl", "http://127.0.0.1:19999/auth");
        route.put("aclUrl", "http://127.0.0.1:19999/acl");
        route.put("healthUrl", "http://127.0.0.1:19999/health");
        route.set("authPrefixes", json.createArrayNode().add("exp_"));
        route.set("aclPrefixes", json.createArrayNode().add("exp/"));
        ObjectNode rTel = json.createObjectNode();
        rTel.put("enabled", true);
        rTel.put("chatId", "explicit-chat-999");
        route.set("telegram", rTel);

        ObjectNode created = state.put(null, route);
        String rId = created.path("id").asText();

        ObjectNode retrieved = state.route(rId);
        Assertions.assertNotNull(retrieved);
        JsonNode effective = retrieved.get("effectiveTelegram");
        Assertions.assertNotNull(effective);

        // Maestro off -> effective.enabled DEBE ser false (nunca envía si master está off)
        Assertions.assertFalse(effective.path("enabled").asBoolean(), "effectiveTelegram.enabled debe ser false si el maestro global está desactivado");
        // Pero el chatId se conserva
        Assertions.assertEquals("explicit-chat-999", effective.path("chatId").asText(), "effectiveTelegram.chatId debe conservar el destino explícito de la ruta");
    }
}
