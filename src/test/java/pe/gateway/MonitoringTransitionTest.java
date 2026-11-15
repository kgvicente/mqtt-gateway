package pe.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Pruebas unitarias puras de Fase 2 (JUnit 5 puro, sin @QuarkusTest ni arranque de scheduler/disco):
 * - Control absoluto de transiciones de histéresis (3 fallos abre, 2 éxitos recupera).
 * - Fechas exactas de inicio (primer sample de racha) y fin (primer sample de recuperación).
 * - Soporte estricto de Epoch 0 (1970-01-01T00:00:00Z) sin sentinelas erróneos.
 * - awaitingObservation pasa a false en la primera muestra aceptada (incluso con 1 fallo).
 * - Estado confirmado se mantiene Offline si había incidente abierto al iniciar.
 * - Reseteo de rachas ante muestras contradictorias en rachas no confirmadas.
 * - Sondeo manual (manual=true) estrictamente no-op sobre contadores, historial e incidentes.
 * - Salto de tiempo excesivo no inventa uptime en métricas.
 * - Comportamiento de umbral 1 (1 fallo abre, 1 éxito recupera inmediatamente).
 * - Primer éxito inicial confirma salud sin emitir falsos incidentes de recuperación.
 */
public class MonitoringTransitionTest {

    private GatewayState state;
    private ObjectMapper json;
    private FakeStorageService fakeStorage;
    private String testRouteId;
    private final String backendName = "Test Pure Deterministic Backend";

    static class FakeStorageService extends StorageService {
        final Map<String, ObjectNode> routes = new ConcurrentHashMap<>();
        final Map<String, String> settings = new ConcurrentHashMap<>();
        final List<ObjectNode> incidents = new CopyOnWriteArrayList<>();
        volatile BiConsumer<String, String> saveSettingInterceptor;

        @Override
        public void init() {}

        @Override
        public boolean isHealthy() { return true; }

        @Override
        public void saveRouteSync(ObjectNode route) {
            routes.put(route.path("id").asText(), route);
        }

        @Override
        public void saveRouteAtomicSync(ObjectNode route, ObjectNode closedIncidentOrNull, boolean cancelPendingForBackend) {
            saveRouteAtomicSync(route, closedIncidentOrNull, cancelPendingForBackend, null, false);
        }

        @Override
        public void saveRouteAtomicSync(ObjectNode route, ObjectNode closedIncidentOrNull, boolean cancelPendingForBackend,
                                        String newApiKeyToPersist, boolean shouldDeleteApiKey) {
            routes.put(route.path("id").asText(), route);
            if (closedIncidentOrNull != null) {
                incidents.add(closedIncidentOrNull);
            }
        }

        @Override
        public void deleteRouteSync(String id) {
            routes.remove(id);
        }

        @Override
        public void deleteRouteAtomicSync(String id, ObjectNode closedIncidentOrNull) {
            routes.remove(id);
            if (closedIncidentOrNull != null) {
                incidents.add(closedIncidentOrNull);
            }
        }

        @Override
        public void saveIncidentWithOutboxSync(ObjectNode incident, ObjectNode outboxItemOrNull) {
            incidents.add(incident);
        }

        @Override
        public void saveSettingSync(String key, String value) {
            BiConsumer<String, String> interceptor = saveSettingInterceptor;
            if (interceptor != null) {
                interceptor.accept(key, value);
            }
            settings.put(key, value);
        }

        @Override
        public void saveSettingsAtomicSync(Map<String, String> settingsMap, boolean cancelAllPending) {
            saveSettingsAtomicSync(settingsMap, cancelAllPending, null, false);
        }

        @Override
        public void saveSettingsAtomicSync(Map<String, String> settingsMap, boolean cancelAllPending,
                                          String newBotTokenToPersist, boolean shouldDeleteBotToken) {
            if (settingsMap != null) {
                BiConsumer<String, String> interceptor = saveSettingInterceptor;
                if (interceptor != null && settingsMap.containsKey("monitoring")) {
                    interceptor.accept("monitoring", settingsMap.get("monitoring"));
                }
                settings.putAll(settingsMap);
            }
        }

        @Override
        public boolean hasSecretSync(String owner) {
            return false;
        }

        @Override
        public void saveIncidentAsync(ObjectNode incident) {
            incidents.add(incident);
        }

        @Override
        public void saveCheckpointAsync(String date, String stateJson) {}

        @Override
        public void close(String lastDate, String lastStateJson) {}
    }

    private static GatewayConfig createFakeConfig() {
        return (GatewayConfig) Proxy.newProxyInstance(
            GatewayConfig.class.getClassLoader(),
            new Class<?>[]{GatewayConfig.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("dataDir")) return "target/fake-memory";
                if (name.equals("timeoutMs")) return 2000L;
                if (name.equals("probeSeconds")) return 30;
                if (name.equals("adminAuthProvider")) return "internal";
                if (name.equals("adminUsername")) return "admin";
                if (name.equals("adminPassword")) return "test-admin";
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

    @BeforeEach
    public void setup() {
        json = new ObjectMapper();
        fakeStorage = new FakeStorageService();
        state = new GatewayState();
        state.json = json;
        state.config = createFakeConfig();
        state.storage = fakeStorage;
        state.hostMetrics = new HostMetricsService();

        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ObjectNode route = json.createObjectNode();
        route.put("name", backendName + " " + unique);
        route.put("enabled", true);
        route.put("authUrl", "http://127.0.0.1:19090/auth");
        route.put("aclUrl", "http://127.0.0.1:19090/acl");
        route.put("healthUrl", "http://127.0.0.1:19090/health");
        route.set("authPrefixes", json.createArrayNode().add("det" + unique + "_"));
        route.set("aclPrefixes", json.createArrayNode().add("det" + unique + "/"));

        ObjectNode monitoring = json.createObjectNode();
        monitoring.put("probeIntervalSeconds", 30);
        monitoring.put("probeTimeoutMs", 2000);
        monitoring.put("failureThreshold", 3);
        monitoring.put("recoveryThreshold", 2);
        route.set("monitoring", monitoring);

        ObjectNode created = state.put(null, route);
        testRouteId = created.path("id").asText();
    }

    @AfterEach
    public void tearDown() {
        if (testRouteId != null) {
            try { state.delete(testRouteId); } catch (Exception ignored) {}
        }
        if (state != null && state.client != null) {
            try { state.client.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    public void testHysteresis3FailuresOpensIncidentWithFirstSampleDate() {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");

        // 1er fallo
        state.recordProbeSample(testRouteId, false, 503, 15.0, t0, false);
        GatewayState.Stats s1 = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(1, s1.consecutiveFailures, "Debe registrar 1 fallo consecutivo");
        Assertions.assertEquals("failure", s1.pendingState, "pendingState debe ser failure");
        Assertions.assertFalse(s1.awaitingObservation, "awaitingObservation debe ser false tras la 1ra muestra");
        Assertions.assertNull(findOpenIncident(testRouteId), "No debe haber incidente abierto con 1 fallo");

        // 2do fallo (30s después)
        Instant t1 = t0.plusSeconds(30);
        state.recordProbeSample(testRouteId, false, 503, 20.0, t1, false);
        GatewayState.Stats s2 = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(2, s2.consecutiveFailures, "Debe tener 2 fallos consecutivos");
        Assertions.assertNull(findOpenIncident(testRouteId), "No debe haber incidente abierto con 2 fallos");

        // 3er fallo (60s después) -> Abre incidente
        Instant t2 = t0.plusSeconds(60);
        state.recordProbeSample(testRouteId, false, 503, 25.0, t2, false);
        GatewayState.Stats s3 = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(0, s3.consecutiveFailures, "Contador debe reiniciarse tras confirmar apertura");
        Assertions.assertNull(s3.pendingState, "pendingState debe ser null tras confirmar apertura");
        Assertions.assertFalse(s3.online, "Debe estar confirmado como offline");

        ObjectNode openInc = findOpenIncident(testRouteId);
        Assertions.assertNotNull(openInc, "El incidente DEBE estar abierto tras el 3er fallo consecutivo");
        Assertions.assertEquals(t0.toString(), openInc.path("startedAt").asText(), "startedAt debe ser exactamente la fecha del 1er fallo");
        Assertions.assertTrue(openInc.hasNonNull("backend"), "El incidente debe tener la propiedad 'backend'");
        Assertions.assertFalse(openInc.has("backendName"), "El incidente no debe usar 'backendName' en el JSON");
        Assertions.assertTrue(openInc.path("backend").asText().contains(backendName), "El campo 'backend' debe contener el nombre de la ruta");

        // Los sondeos de monitoreo no deben inflar el tráfico (count ni samples)
        Assertions.assertEquals(0L, s3.count, "Los sondeos de monitoreo no deben incrementar count de tráfico");
        Assertions.assertTrue(s3.samples.isEmpty(), "Los sondeos de monitoreo no deben agregar elementos a samples de tráfico");
    }

    @Test
    public void testEpoch0AsValidFirstSampleTimestamp() {
        Instant tEpoch = Instant.EPOCH; // 1970-01-01T00:00:00Z (timestamp 0)

        // 3 fallos consecutivos iniciando en Epoch 0
        state.recordProbeSample(testRouteId, false, 503, 10.0, tEpoch, false);
        state.recordProbeSample(testRouteId, false, 503, 10.0, tEpoch.plusSeconds(30), false);
        state.recordProbeSample(testRouteId, false, 503, 10.0, tEpoch.plusSeconds(60), false);

        ObjectNode openInc = findOpenIncident(testRouteId);
        Assertions.assertNotNull(openInc, "El incidente debe abrirse tras 3 fallos");
        Assertions.assertEquals(tEpoch.toString(), openInc.path("startedAt").asText(), "startedAt debe conservar exactamente Epoch 0");
    }

    @Test
    public void testInterruptedFailureSequenceResetsCounters() {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");

        // 2 fallos consecutivos
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0, false);
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(30), false);
        Assertions.assertEquals(2, state.getStatsForTesting(testRouteId).consecutiveFailures);

        // 1 éxito intermedio -> debe reiniciar la racha de fallos
        state.recordProbeSample(testRouteId, true, 200, 10.0, t0.plusSeconds(60), false);
        GatewayState.Stats s = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(0, s.consecutiveFailures, "Éxito intermedio debió reiniciar consecutiveFailures a 0");
        Assertions.assertNull(s.pendingState, "pendingState debió limpiarse");
        Assertions.assertNull(findOpenIncident(testRouteId), "No debe abrirse incidente");

        // 1 nuevo fallo -> inicia en 1
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(90), false);
        Assertions.assertEquals(1, state.getStatsForTesting(testRouteId).consecutiveFailures);
        Assertions.assertNull(findOpenIncident(testRouteId));
    }

    @Test
    public void testHysteresis2SuccessesRecoversIncidentWithFirstSuccessDate() {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");

        // Provocar apertura con 3 fallos
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0, false);
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(30), false);
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(60), false);
        Assertions.assertNotNull(findOpenIncident(testRouteId), "Incidente debe estar abierto");

        // 1er éxito de recuperación (T_rec0)
        Instant tRec0 = t0.plusSeconds(90);
        state.recordProbeSample(testRouteId, true, 200, 15.0, tRec0, false);
        GatewayState.Stats s1 = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(1, s1.consecutiveSuccesses, "Debe registrar 1 éxito consecutivo");
        Assertions.assertEquals("recovery", s1.pendingState, "pendingState debe ser recovery");
        Assertions.assertFalse(s1.online, "El estado confirmado debe seguir offline hasta cumplir umbral");
        Assertions.assertNotNull(findOpenIncident(testRouteId), "Incidente debe seguir abierto tras 1 solo éxito");

        // 2do éxito de recuperación (T_rec1) -> Cierra incidente
        Instant tRec1 = tRec0.plusSeconds(30);
        state.recordProbeSample(testRouteId, true, 200, 12.0, tRec1, false);
        GatewayState.Stats s2 = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(0, s2.consecutiveSuccesses, "Contador debe reiniciarse tras confirmar recuperación");
        Assertions.assertNull(s2.pendingState, "pendingState debe ser null tras confirmar recuperación");
        Assertions.assertTrue(s2.online, "Debe estar confirmado como online");
        Assertions.assertNull(findOpenIncident(testRouteId), "No debe haber incidentes abiertos");

        ObjectNode closedInc = findLastClosedIncident(testRouteId);
        Assertions.assertNotNull(closedInc);
        Assertions.assertEquals(tRec0.toString(), closedInc.path("endedAt").asText(), "endedAt debe ser la fecha del 1er éxito de la racha");
        Assertions.assertEquals("Recovered", closedInc.path("endReason").asText());
        Assertions.assertTrue(closedInc.path("durationMs").asLong() > 0);
    }

    @Test
    public void testInterruptedRecoverySequenceResetsCounters() {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");

        // Abrir incidente
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0, false);
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(30), false);
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(60), false);
        Assertions.assertNotNull(findOpenIncident(testRouteId));

        // 1er éxito
        state.recordProbeSample(testRouteId, true, 200, 10.0, t0.plusSeconds(90), false);
        Assertions.assertEquals(1, state.getStatsForTesting(testRouteId).consecutiveSuccesses);

        // Fallo intermedio -> resetea consecutiveSuccesses
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(120), false);
        GatewayState.Stats s = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(0, s.consecutiveSuccesses, "Fallo intermedio debe resetear consecutiveSuccesses a 0");
        Assertions.assertNull(s.pendingState);
        Assertions.assertNotNull(findOpenIncident(testRouteId), "Incidente debe seguir abierto");
    }

    @Test
    public void testManualProbeIsStrictlyNoOpOnCountersAndIncidents() {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");

        // 1 fallo automático
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0, false);
        Assertions.assertEquals(1, state.getStatsForTesting(testRouteId).consecutiveFailures);

        // Sondeos manuales con fallo y con éxito
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(5), true);
        Assertions.assertEquals(1, state.getStatsForTesting(testRouteId).consecutiveFailures, "Sondeo manual no debe alterar contadores");

        state.recordProbeSample(testRouteId, true, 200, 10.0, t0.plusSeconds(10), true);
        Assertions.assertEquals(1, state.getStatsForTesting(testRouteId).consecutiveFailures, "Sondeo manual no debe resetear contadores");
    }

    @Test
    public void testExcessiveIntervalDoesNotInventUptime() {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");
        state.recordProbeSample(testRouteId, true, 200, 10.0, t0, false);
        long up0 = state.getStatsForTesting(testRouteId).upMs;

        // Salto excesivo de 2 horas
        Instant tBig = t0.plusSeconds(7200);
        state.recordProbeSample(testRouteId, true, 200, 10.0, tBig, false);
        long up1 = state.getStatsForTesting(testRouteId).upMs;

        Assertions.assertEquals(up0, up1, "No debe acumular uptime en intervalos desconocidos o excesivos");
    }

    @Test
    public void testThreshold1OpensAndRecoversImmediately() {
        ObjectNode r = state.route(testRouteId);
        ObjectNode mon = (ObjectNode) r.get("monitoring");
        mon.put("failureThreshold", 1);
        mon.put("recoveryThreshold", 1);
        state.put(testRouteId, r);

        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");
        // 1 fallo abre de inmediato
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0, false);
        ObjectNode openInc = findOpenIncident(testRouteId);
        Assertions.assertNotNull(openInc, "Con umbral 1, un solo fallo debe abrir incidente");
        Assertions.assertEquals(t0.toString(), openInc.path("startedAt").asText());

        // 1 éxito recupera de inmediato
        Instant t1 = t0.plusSeconds(30);
        state.recordProbeSample(testRouteId, true, 200, 10.0, t1, false);
        Assertions.assertNull(findOpenIncident(testRouteId), "Con umbral 1, un solo éxito debe recuperar");
        ObjectNode closed = findLastClosedIncident(testRouteId);
        Assertions.assertNotNull(closed);
        Assertions.assertEquals(t1.toString(), closed.path("endedAt").asText());
    }

    @Test
    public void testInitialHealthyProbeConfirmsWithoutFalseRecovery() {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");

        state.recordProbeSample(testRouteId, true, 200, 10.0, t0, false);
        GatewayState.Stats s = state.getStatsForTesting(testRouteId);
        Assertions.assertNotNull(s, "Stats deben existir");
        Assertions.assertTrue(s.online, "Debe confirmar salud");
        Assertions.assertFalse(s.awaitingObservation, "Ya no debe esperar observación");
        Assertions.assertNull(findLastClosedIncident(testRouteId), "No debe emitir falso incidente de recuperación al iniciar");
    }

    @Test
    public void testConcurrentUpdateSettingsAndRoutePutRejectsInvalidCombination() throws Exception {
        CountDownLatch inSaveSetting = new CountDownLatch(1);
        CountDownLatch proceedSaveSetting = new CountDownLatch(1);
        AtomicReference<Throwable> updateSettingsError = new AtomicReference<>();
        AtomicReference<Throwable> putError = new AtomicReference<>();
        CountDownLatch putFinished = new CountDownLatch(1);

        fakeStorage.saveSettingInterceptor = (key, value) -> {
            if ("monitoring".equals(key)) {
                inSaveSetting.countDown();
                try {
                    proceedSaveSetting.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        Thread updateSettingsThread = new Thread(() -> {
            try {
                ObjectNode body = json.createObjectNode();
                ObjectNode mon = body.putObject("monitoring");
                mon.put("probeIntervalSeconds", 3);
                mon.put("probeTimeoutMs", 1000);
                mon.put("failureThreshold", 3);
                mon.put("recoveryThreshold", 2);
                state.updateSettings(body);
            } catch (Throwable t) {
                updateSettingsError.set(t);
            }
        }, "test-update-settings");

        Thread putRouteThread = new Thread(() -> {
            try {
                // Esperar a que updateSettings haya entrado a saveSettingSync manteniendo routeLock
                inSaveSetting.await(5, TimeUnit.SECONDS);
                // Preparar payload con timeout 3500ms (válido en global viejo de 30s/2000ms, pero inválido en nuevo 3s/1000ms)
                ObjectNode existingRoute = state.route(testRouteId).deepCopy();
                ObjectNode mon = json.createObjectNode();
                mon.put("probeTimeoutMs", 3500);
                existingRoute.set("monitoring", mon);
                state.put(testRouteId, existingRoute);
            } catch (Throwable t) {
                putError.set(t);
            } finally {
                putFinished.countDown();
            }
        }, "test-concurrent-put");

        try {
            updateSettingsThread.start();
            Assertions.assertTrue(inSaveSetting.await(5, TimeUnit.SECONDS), "updateSettings debió entrar a saveSettingSync");

            putRouteThread.start();
            // Verificar que el PUT está bloqueado esperando routeLock mientras updateSettings está dentro de saveSettingSync
            Thread.sleep(150);
            Assertions.assertEquals(1, putFinished.getCount(), "PUT de ruta debe esperar a routeLock");

            // Liberar updateSettings para que comitee el nuevo global
            proceedSaveSetting.countDown();
            updateSettingsThread.join(5000);
            Assertions.assertTrue(putFinished.await(5, TimeUnit.SECONDS), "El PUT debió concluir tras liberar routeLock");
            putRouteThread.join(5000);

            // Verificar que los hilos terminaron
            Assertions.assertFalse(updateSettingsThread.isAlive(), "updateSettingsThread debe haber terminado");
            Assertions.assertFalse(putRouteThread.isAlive(), "putRouteThread debe haber terminado");

            // Verificar que updateSettings no arrojó excepción
            Assertions.assertNull(updateSettingsError.get(), "updateSettings debió completarse sin error");

            // Afirmar que el nuevo global (3s) fue persistido y publicado
            Assertions.assertEquals(3, state.settings().path("monitoring").path("probeIntervalSeconds").asInt(), "El nuevo global de 3s debe estar publicado");
            Assertions.assertEquals(1000, state.settings().path("monitoring").path("probeTimeoutMs").asInt());
            Assertions.assertTrue(fakeStorage.settings.containsKey("monitoring"), "El nuevo global debe estar persistido en storage");
            Assertions.assertTrue(fakeStorage.settings.get("monitoring").contains("\"probeIntervalSeconds\":3"), "Storage debe contener probeIntervalSeconds: 3");

            // Verificar que el PUT fue rechazado tras el commit del nuevo global
            Assertions.assertNotNull(putError.get(), "El PUT de ruta debió ser rechazado por invalidar el nuevo intervalo");
            Assertions.assertInstanceOf(jakarta.ws.rs.BadRequestException.class, putError.get());

            // Verificar que en storage la ruta no tiene timeout inválido
            ObjectNode storedRoute = fakeStorage.routes.get(testRouteId);
            if (storedRoute != null && storedRoute.has("monitoring")) {
                Assertions.assertNotEquals(3500, storedRoute.path("monitoring").path("probeTimeoutMs").asInt());
            }
        } finally {
            proceedSaveSetting.countDown();
            fakeStorage.saveSettingInterceptor = null;
            updateSettingsThread.join(1000);
            putRouteThread.join(1000);
        }
    }

    @Test
    public void testStreakResetOnHealthUrlChangeNotOnMetadata() {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");

        // 1er fallo
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0, false);
        // 2do fallo
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(30), false);
        GatewayState.Stats s = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(2, s.consecutiveFailures, "Debe registrar 2 fallos consecutivos");
        Assertions.assertEquals("failure", s.pendingState);

        // Edición de sólo metadata (name) -> NO resetea rachas
        ObjectNode existingRoute = state.route(testRouteId).deepCopy();
        existingRoute.put("name", "Nuevo Nombre Metadata");
        state.put(testRouteId, existingRoute);

        s = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(2, s.consecutiveFailures, "Cambio de sólo metadata NO debe resetear consecutiveFailures");
        Assertions.assertEquals("failure", s.pendingState, "Cambio de metadata debe mantener pendingState");

        // Edición de identidad de sondeo (healthUrl) -> DEBE resetear rachas
        existingRoute = state.route(testRouteId).deepCopy();
        existingRoute.put("healthUrl", "http://127.0.0.1:19090/new_health");
        state.put(testRouteId, existingRoute);

        s = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(0, s.consecutiveFailures, "Cambio de healthUrl DEBE resetear consecutiveFailures a 0");
        Assertions.assertNull(s.pendingState, "Cambio de healthUrl debe limpiar pendingState");

        // Siguiente fallo tras el cambio de healthUrl: debe ser 1 (no 3), y NO abrir incidente
        state.recordProbeSample(testRouteId, false, 503, 10.0, t0.plusSeconds(60), false);
        s = state.getStatsForTesting(testRouteId);
        Assertions.assertEquals(1, s.consecutiveFailures, "El siguiente fallo debe registrarse como 1");
        Assertions.assertEquals("failure", s.pendingState);
        Assertions.assertNull(findOpenIncident(testRouteId), "No debe abrirse incidente con el primer fallo del nuevo endpoint");
    }

    @Test
    public void testProbeSamplesDoNotInflateTrafficCountOrSamples() {
        Instant t0 = Instant.parse("2026-11-14T10:00:00Z");

        // Inyectar varias muestras de sondeos (éxitos y fallos)
        state.recordProbeSample(testRouteId, true, 200, 15.0, t0, false);
        state.recordProbeSample(testRouteId, false, 503, 20.0, t0.plusSeconds(30), false);
        state.recordProbeSample(testRouteId, true, 200, 12.0, t0.plusSeconds(60), false);

        GatewayState.Stats s = state.getStatsForTesting(testRouteId);

        // Los sondeos de monitoreo van a probeHistory y probeMs, NUNCA a count ni samples de tráfico
        Assertions.assertEquals(0L, s.count, "Los sondeos de monitoreo no deben incrementar el contador de tráfico count");
        Assertions.assertTrue(s.samples.isEmpty(), "Los sondeos de monitoreo no deben agregar muestras a la cola de latencias de tráfico samples");
        Assertions.assertEquals(3, s.probeHistory.size(), "probeHistory sí debe contener las muestras de monitoreo");
        Assertions.assertEquals(12.0, s.probeMs, "probeMs debe reflejar la latencia del último sondeo");
    }

    private ObjectNode findOpenIncident(String routeId) {
        ArrayNode arr = state.incidents();
        for (int i = 0; i < arr.size(); i++) {
            ObjectNode item = (ObjectNode) arr.get(i);
            if (item.path("backendId").asText().equals(routeId) && item.path("endedAt").isNull()) {
                return item;
            }
        }
        return null;
    }

    private ObjectNode findLastClosedIncident(String routeId) {
        ArrayNode arr = state.incidents();
        for (int i = arr.size() - 1; i >= 0; i--) {
            ObjectNode item = (ObjectNode) arr.get(i);
            if (item.path("backendId").asText().equals(routeId) && !item.path("endedAt").isNull()) {
                return item;
            }
        }
        return null;
    }
}
