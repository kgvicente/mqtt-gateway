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
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GatewayState {
    public record ProbeSample(String time, boolean online, int status, double latencyMs) {}
    private static final ZoneId ZONE = ZoneId.of("America/Lima");
    private String currentDate;
    @Inject ObjectMapper json;
    @Inject GatewayConfig config;
    @Inject HostMetricsService hostMetrics;
    private static final Logger LOG = Logger.getLogger(GatewayState.class);
    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private ArrayNode routes;
    private ArrayNode incidents;
    private final Deque<ObjectNode> logs = new ArrayDeque<>();
    private final Map<String, Stats> stats = new HashMap<>();
    private final BroadcastProcessor<String> streamBus = BroadcastProcessor.create();
    private ScheduledExecutorService scheduler;
    private Path directory;

    // ponytail: bounded latency window and JDK timer; no database or scheduler dependency.
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
        boolean online;
        boolean known;
        boolean probeOnline;
        double probeMs;
        int probeHttpStatus;
    }

    void start(@Observes StartupEvent event) throws Exception {
        directory = Path.of(config.dataDir());
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        incidents = load("incidents.json");
        routes = load("routes.json");
        if (!Files.exists(directory.resolve("routes.json"))) {
            String[] names = {"IoT Core Service", "Device Gateway", "Telemetry Service"};
            String[] ids = {"core-service", "device-gw", "telemetry"};
            for (int i = 0; i < config.auth().size(); i++) {
                var a = config.auth().get(i);
                var b = config.acl().get(i);
                ObjectNode route = json.createObjectNode().put("id", ids[i]).put("name", names[i])
                        .put("enabled", true).put("authUrl", a.url().toString())
                        .put("aclUrl", b.url().toString()).put("healthUrl", a.url().resolve("/").toString())
                        .put("apiKey", a.apiKey().orElse(""));
                route.set("authPrefixes", json.valueToTree(a.prefixes()));
                route.set("aclPrefixes", json.valueToTree(b.prefixes()));
                route.put("createdAt", Instant.now().toString());
                routes.add(route);
            }
            save("routes.json", routes);
        }
        for (JsonNode route : routes) validate(route);
        if (!Files.exists(directory.resolve("incidents.json"))) save("incidents.json", incidents);
        loadDailyState();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-probes"); t.setDaemon(true); return t;
        });
        scheduler.scheduleAtFixedRate(this::probeAll, 1, config.probeSeconds(), TimeUnit.SECONDS);
    }

    void stop(@Observes ShutdownEvent event) {
        if (scheduler != null) scheduler.shutdownNow();
        saveDailyState();
    }

    private synchronized void checkDateRotation() {
        String today = LocalDate.now(ZONE).toString();
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
                s.observed = System.currentTimeMillis();
            }
            saveDailyState();
            notifyUpdate();
        }
    }

    private synchronized void loadDailyState() {
        try {
            Path path = directory.resolve("daily-state.json");
            String today = LocalDate.now(ZONE).toString();
            currentDate = today;
            if (!Files.exists(path)) return;
            JsonNode root = json.readTree(Files.readString(path));
            if (root == null || !root.isObject()) return;
            String savedDate = safe(root, "date");
            if (!today.equals(savedDate)) {
                LOG.infof("Discarding previous day data from %s (today is %s)", savedDate, today);
                return;
            }
            logs.clear();
            if (root.path("logs").isArray()) {
                for (JsonNode l : root.path("logs")) {
                    if (l.isObject()) logs.add((ObjectNode) l);
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
                        for (JsonNode sm : sn.path("samples")) s.samples.add(sm.asDouble());
                    }
                    s.probeHistory.clear();
                    if (sn.path("probes").isArray()) {
                        for (JsonNode p : sn.path("probes")) {
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
            LOG.infof("Daily state restored for date %s (%d logs, %d backends)", today, logs.size(), stats.size());
        } catch (Exception e) {
            LOG.warn("Could not load daily-state.json", e);
        }
    }

    private synchronized void saveDailyState() {
        try {
            ObjectNode root = json.createObjectNode();
            root.put("date", currentDate != null ? currentDate : LocalDate.now(ZONE).toString());
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
            save("daily-state.json", root);
        } catch (Exception e) {
            LOG.warn("Could not persist daily-state.json", e);
        }
    }

    private ArrayNode load(String name) throws Exception {
        Path path = directory.resolve(name);
        if (!Files.exists(path)) return json.createArrayNode();
        JsonNode value = json.readTree(Files.readString(path));
        if (value == null || !value.isArray()) throw new IllegalStateException("Invalid " + name);
        return (ArrayNode) value;
    }

    private void save(String name, JsonNode value) {
        try {
            Path temp = directory.resolve(name + ".tmp");
            Files.writeString(temp, json.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist " + name, e);
        }
    }

    synchronized ArrayNode routes(boolean secrets) {
        ArrayNode copy = routes.deepCopy();
        if (!secrets) for (JsonNode r : copy) {
            ((ObjectNode) r).put("hasApiKey", !r.path("apiKey").asText("").isEmpty()).remove("apiKey");
        }
        return copy;
    }

    synchronized ObjectNode route(String id) {
        for (JsonNode route : routes) if (route.path("id").asText().equals(id)) return route.deepCopy();
        throw new jakarta.ws.rs.NotFoundException("Backend not found");
    }

    private void validate(JsonNode route) {
        if (!route.isObject() || !route.path("name").isTextual() || route.path("name").asText().isBlank()
                || !route.path("enabled").isBoolean()) throw new BadRequestException("Name and enabled are required");
        for (String key : List.of("authUrl", "aclUrl", "healthUrl")) {
            try {
                URI uri = URI.create(route.path(key).asText());
                if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            } catch (Exception e) { throw new BadRequestException("Invalid " + key); }
        }
        if (route.has("apiKey") && (!route.path("apiKey").isTextual()
                || route.path("apiKey").asText().contains("\r") || route.path("apiKey").asText().contains("\n")))
            throw new BadRequestException("Invalid API key");
        for (String key : List.of("authPrefixes", "aclPrefixes")) {
            JsonNode prefixes = route.path(key);
            if (!prefixes.isArray() || prefixes.isEmpty()) throw new BadRequestException("Prefixes required");
            for (JsonNode prefix : prefixes) if (!prefix.isTextual() || prefix.asText().isBlank())
                throw new BadRequestException("Invalid prefix");
        }
    }

    synchronized ObjectNode put(String id, ObjectNode input) {
        ObjectNode next = input.deepCopy();
        if (id == null) {
            id = UUID.randomUUID().toString();
            next.put("apiKey", next.path("apiKey").asText(""));
            next.put("createdAt", Instant.now().toString());
        } else {
            ObjectNode old = route(id);
            if (!next.has("apiKey")) next.set("apiKey", old.path("apiKey"));
            next.set("createdAt", old.path("createdAt"));
        }
        next.retain("name", "enabled", "authUrl", "aclUrl", "healthUrl", "authPrefixes", "aclPrefixes", "apiKey", "createdAt");
        next.put("id", id);
        validate(next);
        ArrayNode updated = routes.deepCopy();
        for (int i = 0; i < updated.size(); i++) if (updated.get(i).path("id").asText().equals(id)) { updated.remove(i); break; }
        updated.add(next);
        // Reject ambiguous matches instead of making routing depend on UI ordering.
        Set<String> auth = new HashSet<>(), acl = new HashSet<>();
        for (JsonNode r : updated) if (r.path("enabled").asBoolean())
            for (String key : List.of("authPrefixes", "aclPrefixes"))
                for (JsonNode prefix : r.path(key))
                    if (!(key.equals("authPrefixes") ? auth : acl).add(prefix.asText()))
                        throw new BadRequestException("Duplicate prefix: " + prefix.asText());
        save("routes.json", updated);
        routes = updated;
        if (!next.path("enabled").asBoolean()) {
            Stats stat = stats.get(id);
            if (stat != null) { stat.lastProbe = 0; stat.known = false; }
            closeIncident(id, "Monitoring paused");
        }
        ObjectNode response = next.deepCopy();
        response.put("hasApiKey", !response.path("apiKey").asText("").isEmpty()).remove("apiKey");
        notifyUpdate();
        return response;
    }

    synchronized void delete(String id) {
        route(id);
        ArrayNode updated = routes.deepCopy();
        for (int i = 0; i < updated.size(); i++) if (updated.get(i).path("id").asText().equals(id)) { updated.remove(i); break; }
        save("routes.json", updated);
        routes = updated;
        closeIncident(id, "Backend removed");
        stats.remove(id);
        notifyUpdate();
    }

    private void closeIncident(String id, String endReason) {
        boolean changed = false;
        for (JsonNode n : incidents) if (n.path("backendId").asText().equals(id) && n.path("endedAt").isNull()) {
            ObjectNode incident = (ObjectNode) n;
            incident.put("endedAt", Instant.now().toString());
            incident.put("durationMs", System.currentTimeMillis() - Instant.parse(n.path("startedAt").asText()).toEpochMilli());
            incident.put("endReason", endReason);
            changed = true;
        }
        if (changed) save("incidents.json", incidents);
    }

    ObjectNode match(String value, boolean auth) {
        ObjectNode best = null;
        int length = -1;
        for (JsonNode r : routes(true)) {
            if (!r.path("enabled").asBoolean()) continue;
            for (JsonNode prefix : r.path(auth ? "authPrefixes" : "aclPrefixes"))
                if (value.startsWith(prefix.asText()) && prefix.asText().length() > length) {
                    best = (ObjectNode) r; length = prefix.asText().length();
                }
        }
        return best;
    }

    synchronized void audit(boolean auth, JsonNode request, String result, String backend, double ms) {
        checkDateRotation();
        ObjectNode entry = json.createObjectNode().put("timestamp", Instant.now().toString())
                .put("type", auth ? "auth" : "acl").put("client", safe(request, "clientid"))
                .put("topic", safe(request, "topic")).put("result", result).put("backend", backend).put("latencyMs", ms);
        if (logs.size() == 2000) logs.removeFirst();
        logs.addLast(entry);
    }

    static String safe(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() ? node.path(field).asText() : "";
    }

    synchronized ArrayNode logs() {
        ArrayNode result = json.createArrayNode();
        logs.descendingIterator().forEachRemaining(e -> result.add(e.deepCopy()));
        return result;
    }

    synchronized void clearLogs() {
        logs.clear();
        saveDailyState();
        notifyUpdate();
    }

    public synchronized String stateSnapshotJson() {
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("routes", routes(false));
        snapshot.set("metrics", metrics());
        snapshot.set("incidents", incidents());
        snapshot.set("logs", logs());
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

    synchronized void sample(String id, double ms) {
        checkDateRotation();
        Stats s = stats.computeIfAbsent(id, k -> new Stats());
        s.current = ms; s.count++; s.total += ms; s.min = Math.min(ms, s.min); s.max = Math.max(ms, s.max);
        if (s.samples.size() == 2000) s.samples.removeFirst();
        s.samples.add(ms);
    }

    synchronized void availability(String id, String reason, double probeMs, boolean probe) {
        checkDateRotation();
        boolean exists = false;
        for (JsonNode current : routes) if (current.path("id").asText().equals(id) && current.path("enabled").asBoolean()) exists = true;
        if (!exists) return;
        long now = System.currentTimeMillis();
        Stats s = stats.computeIfAbsent(id, k -> new Stats());
        if (probe) {
            if (s.known && s.lastProbe > 0) {
                // ponytail: only account for observed intervals; process downtime is unknown.
                long delta = now - s.lastProbe;
                if (delta <= config.probeSeconds() * 2000L) {
                    if (s.probeOnline) s.upMs += delta; else s.downMs += delta;
                }
            }
            s.lastProbe = now; s.probeMs = probeMs; s.probeOnline = reason == null;
            if (s.probeHistory.size() >= 2880) s.probeHistory.removeFirst();
            s.probeHistory.addLast(new ProbeSample(Instant.ofEpochMilli(now).toString(), reason == null, s.probeHttpStatus, probeMs));
        }
        if (s.observed == 0) s.observed = now;
        s.known = true; s.online = reason == null;
        ObjectNode open = null;
        for (JsonNode incident : incidents) if (incident.path("backendId").asText().equals(id) && incident.path("endedAt").isNull())
            open = (ObjectNode) incident;
        if (reason != null && open == null) {
            ObjectNode incident = json.createObjectNode().put("id", UUID.randomUUID().toString())
                    .put("backendId", id).put("backend", route(id).path("name").asText())
                    .put("startedAt", Instant.ofEpochMilli(now).toString()).put("reason", reason);
            incident.putNull("endedAt"); incident.putNull("durationMs");
            incidents.add(incident);
            save("incidents.json", incidents);
        } else if (reason == null && open != null) {
            open.put("endReason", "Recovered");
            open.put("endedAt", Instant.ofEpochMilli(now).toString());
            open.put("durationMs", now - Instant.parse(open.path("startedAt").asText()).toEpochMilli());
            save("incidents.json", incidents);
        }
    }

    synchronized ArrayNode incidents() {
        ArrayNode copy = incidents.deepCopy();
        for (JsonNode n : copy) if (n.path("endedAt").isNull())
            ((ObjectNode) n).put("durationMs", System.currentTimeMillis() - Instant.parse(n.path("startedAt").asText()).toEpochMilli());
        return copy;
    }

    synchronized ArrayNode metrics() {
        ArrayNode result = json.createArrayNode();
        for (JsonNode r : routes) {
            String id = r.path("id").asText();
            Stats s = stats.get(id);
            ObjectNode m = result.addObject().put("id", id).put("name", r.path("name").asText());
            ArrayNode history = m.putArray("probeHistory");
            ArrayNode recentSamples = m.putArray("recentSamples");
            if (s != null) {
                // ponytail: devolver el historial retenido permite filtrar por fecha sin otra persistencia.
                for (ProbeSample sample : s.probeHistory) {
                    history.addObject().put("time", sample.time()).put("online", sample.online())
                            .put("status", sample.status()).put("latencyMs", sample.latencyMs());
                }
                int skip = Math.max(0, s.samples.size() - 25);
                int i = 0;
                for (Double d : s.samples) if (i++ >= skip) recentSamples.add(d);
            }
            boolean enabled = r.path("enabled").asBoolean();
            m.put("status", !enabled ? "Paused" : s == null || !s.known ? "Unknown" : !s.online ? "Offline"
                    : s.current > 1000 ? "Slow" : s.current >= 200 ? "Degraded" : "Normal");
            if (s != null) {
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
            } else { m.put("count", 0); m.putNull("uptimePercent"); }
        }
        return result;
    }

    ObjectNode probe(String id) {
        ObjectNode route = route(id);
        if (!route.path("enabled").asBoolean()) throw new BadRequestException("Backend is paused");
        long start = System.nanoTime();
        String reason = null;
        int code = 0;
        try {
            var builder = HttpRequest.newBuilder(URI.create(route.path("healthUrl").asText()))
                    .timeout(Duration.ofMillis(config.timeoutMs())).GET();
            if (!route.path("apiKey").asText("").isEmpty()) builder.header("X-EMQX-API-KEY", route.path("apiKey").asText());
            code = client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code >= 500) reason = "HTTP " + code;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return json.createObjectNode().put("online", false).put("reason", "Probe interrupted");
            }
            reason = e instanceof HttpTimeoutException ? "Timeout" : "Connection failure";
        }
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        synchronized (this) {
            for (JsonNode current : routes) if (current.path("id").asText().equals(id) && current.path("enabled").asBoolean()) {
                Stats s = stats.computeIfAbsent(id, k -> new Stats());
                s.probeHttpStatus = code;
                availability(id, reason, ms, true);
                break;
            }
        }
        saveDailyState();
        notifyUpdate();
        return json.createObjectNode().put("online", reason == null).put("httpStatus", code)
                .put("latencyMs", ms).put("reason", reason == null ? "" : reason);
    }

    private void probeAll() {
        checkDateRotation();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (JsonNode route : routes(true)) if (route.path("enabled").asBoolean())
                workers.submit(() -> {
                    try { probe(route.path("id").asText()); }
                    catch (Exception e) { LOG.error("Backend probe failed", e); }
                });
        }
        saveDailyState();
        notifyUpdate();
    }
}
