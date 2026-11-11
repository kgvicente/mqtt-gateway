package pe.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class HostMetricsService {
    @Inject ObjectMapper json;
    private final boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    private final Path proc;
    private final Path sys;
    private final Path root;
    private ObjectNode cached;
    private long sampledAt;
    private long[] previousCpu;
    private Map<String, long[]> previousNet = Map.of(), previousDisk = Map.of();

    public HostMetricsService() {
        this(Path.of("/proc"), Path.of("/sys"), Path.of(System.getProperty("user.dir")).getRoot());
    }

    // ponytail: rutas sustituibles para probar el formato del kernel sin un agente externo.
    HostMetricsService(Path proc, Path sys, Path root) {
        this.proc = proc; this.sys = sys; this.root = root;
    }

    public synchronized ObjectNode snapshot() {
        long now = System.nanoTime();
        if (cached != null && now - sampledAt < 1_000_000_000L) return cached.deepCopy();
        double seconds = sampledAt == 0 ? 0 : (now - sampledAt) / 1e9;
        ObjectNode out = json.createObjectNode();
        out.put("time", Instant.now().toString()).put("source", linux ? "linux" : "jdk")
                .put("os", System.getProperty("os.name", ""))
                .put("kernel", System.getProperty("os.version", ""))
                .put("architecture", System.getProperty("os.arch", ""))
                .put("cores", Runtime.getRuntime().availableProcessors());
        ObjectNode memory = out.putObject("memory"), swap = out.putObject("swap"), disk = out.putObject("disk");
        ObjectNode cpu = out.putObject("cpu"), net = out.putObject("network"), io = out.putObject("io");
        List<String> unavailable = new ArrayList<>();
        try {
            var store = Files.getFileStore(root);
            long total = store.getTotalSpace(), free = store.getUnallocatedSpace();
            disk.put("total", total).put("free", free).put("used", Math.max(0, total - free)).put("path", root.toString());
        } catch (IOException | RuntimeException e) { unavailable.add("Almacenamiento"); }
        if (linux) {
            try { out.put("uptimeSeconds", Double.parseDouble(read("uptime").trim().split("\\s+")[0])); }
            catch (IOException | RuntimeException e) { unavailable.add("Tiempo de actividad"); }
            try {
                String[] values = read("loadavg").trim().split("\\s+");
                var load = out.putArray("load");
                for (int i = 0; i < 3; i++) load.add(Double.parseDouble(values[i]));
            } catch (IOException | RuntimeException e) { out.remove("load"); unavailable.add("Carga media"); }
            try {
                String text = read("cpuinfo");
                String model = text.lines().filter(line -> line.matches("(?i)(model name|hardware|cpu model)\\s*:.*"))
                        .map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst().orElse(System.getProperty("os.arch"));
                out.put("processor", model);
                long cores = text.lines().filter(line -> line.matches("processor\\s*:.*")).count();
                if (cores > 0) out.put("cores", cores);
            } catch (IOException | RuntimeException e) { unavailable.add("Modelo de procesador"); }
            try {
                Map<String, Long> info = new HashMap<>();
                for (String line : read("meminfo").lines().toList()) {
                    String[] fields = line.trim().split("[:\\s]+");
                    if (fields.length >= 2) info.put(fields[0], Long.parseLong(fields[1]) * 1024);
                }
                usage(memory, info.get("MemTotal"), info.get("MemAvailable"));
                usage(swap, info.get("SwapTotal"), info.get("SwapFree"));
            } catch (IOException | RuntimeException e) { memory.removeAll(); swap.removeAll(); unavailable.add("Memoria y swap"); }
            try {
                long[] current = parseCpu(read("stat"));
                if (previousCpu != null && current[0] > previousCpu[0] && current[1] >= previousCpu[1] && current[2] >= previousCpu[2]) {
                    double total = current[0] - previousCpu[0];
                    cpu.put("totalPercent", clamp(100 * (total - (current[1] - previousCpu[1])) / total))
                            .put("userPercent", clamp(100 * (current[2] - previousCpu[2]) / total));
                }
                previousCpu = current;
            } catch (IOException | RuntimeException e) { previousCpu = null; unavailable.add("Uso de CPU"); }
            try {
                var current = parseNetwork(read("net/dev"));
                long[] sum = new long[6];
                current.values().forEach(values -> { for (int i = 0; i < sum.length; i++) sum[i] += values[i]; });
                net.put("rxBytes", sum[0]).put("txBytes", sum[1]).put("rxPackets", sum[2]).put("txPackets", sum[3])
                        .put("errors", sum[4]).put("drops", sum[5]);
                var names = net.putArray("interfaces"); current.keySet().forEach(names::add);
                if (!previousNet.isEmpty() && seconds > 0) {
                    net.put("rxMiBs", delta(current, previousNet, 0) / seconds / 1048576)
                            .put("txMiBs", delta(current, previousNet, 1) / seconds / 1048576);
                }
                previousNet = current;
            } catch (IOException | RuntimeException e) { previousNet = Map.of(); unavailable.add("Red"); }
            try {
                // ponytail: dispositivos completos de sysfs; excluir particiones y capas virtuales evita contar dos veces.
                var current = parseDisks(read("diskstats"), name -> Files.isDirectory(sys.resolve("block").resolve(name)));
                var names = io.putArray("devices"); current.keySet().forEach(names::add);
                if (!previousDisk.isEmpty() && seconds > 0) {
                    io.put("readsPerSecond", delta(current, previousDisk, 0) / seconds)
                            .put("writesPerSecond", delta(current, previousDisk, 1) / seconds);
                }
                previousDisk = current;
            } catch (IOException | RuntimeException e) { previousDisk = Map.of(); unavailable.add("Operaciones de disco"); }
        } else {
            out.put("processor", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", System.getProperty("os.arch")));
            unavailable.add("Tiempo de actividad, carga media, red y operaciones de disco (requieren Linux)");
            try {
                if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                    long total = os.getTotalMemorySize(), free = os.getFreeMemorySize();
                    if (total > 0 && free >= 0) usage(memory, total, free);
                    long swapTotal = os.getTotalSwapSpaceSize(), swapFree = os.getFreeSwapSpaceSize();
                    if (swapTotal >= 0 && swapFree >= 0) usage(swap, swapTotal, swapFree);
                    double load = os.getCpuLoad();
                    if (load >= 0) cpu.put("totalPercent", clamp(load * 100));
                }
            } catch (RuntimeException | LinkageError e) { unavailable.add("Métricas del sistema JDK"); }
        }
        out.put("waitingForSample", sampledAt == 0);
        var warnings = out.putArray("unavailable"); unavailable.forEach(warnings::add);
        sampledAt = now; cached = out;
        return out.deepCopy();
    }

    private String read(String file) throws IOException { return Files.readString(proc.resolve(file)); }
    private static double clamp(double value) { return Math.max(0, Math.min(100, value)); }
    private static void usage(ObjectNode node, Long total, Long free) {
        if (total == null || free == null) throw new IllegalArgumentException("Contador de memoria no disponible");
        node.put("total", total).put("free", free).put("used", Math.max(0, total - free));
    }
    static long[] parseCpu(String text) {
        String[] fields = text.lines().filter(line -> line.startsWith("cpu ")).findFirst().orElseThrow().trim().split("\\s+");
        if (fields.length < 5) throw new IllegalArgumentException("Contadores CPU incompletos");
        long total = 0;
        // guest y guest_nice ya están incluidos en user/nice.
        for (int i = 1; i < Math.min(fields.length, 9); i++) total += Long.parseLong(fields[i]);
        long idle = Long.parseLong(fields[4]) + (fields.length > 5 ? Long.parseLong(fields[5]) : 0);
        return new long[]{total, idle, Long.parseLong(fields[1]) + Long.parseLong(fields[2])};
    }
    static Map<String, long[]> parseNetwork(String text) {
        Map<String, long[]> result = new TreeMap<>();
        for (String line : text.lines().toList()) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            if (name.equals("lo")) continue;
            String[] f = line.substring(colon + 1).trim().split("\\s+");
            if (f.length < 16) throw new IllegalArgumentException("Contadores de red incompletos");
            result.put(name, new long[]{Long.parseLong(f[0]), Long.parseLong(f[8]), Long.parseLong(f[1]),
                    Long.parseLong(f[9]), Long.parseLong(f[2]) + Long.parseLong(f[10]), Long.parseLong(f[3]) + Long.parseLong(f[11])});
        }
        return result;
    }
    static Map<String, long[]> parseDisks(String text, java.util.function.Predicate<String> wholeDevice) {
        Map<String, long[]> result = new TreeMap<>();
        for (String line : text.lines().toList()) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 14 || f[2].matches("(?:loop|ram|zram|dm-|md).*" ) || !wholeDevice.test(f[2])) continue;
            result.put(f[2], new long[]{Long.parseLong(f[3]), Long.parseLong(f[7])});
        }
        return result;
    }
    static long delta(Map<String, long[]> current, Map<String, long[]> previous, int field) {
        long result = 0;
        for (var entry : current.entrySet()) {
            long[] before = previous.get(entry.getKey());
            if (before != null) result += Math.max(0, entry.getValue()[field] - before[field]);
        }
        return result;
    }
}
