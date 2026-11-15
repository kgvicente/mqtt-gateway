package pe.gateway;

import io.smallrye.config.ConfigMapping;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "gateway")
public interface GatewayConfig {
    @io.smallrye.config.WithDefault("data")
    String dataDir();
    @io.smallrye.config.WithDefault("http://127.0.0.1:18083")
    String emqxUrl();
    @io.smallrye.config.WithDefault("30")
    int probeSeconds();
    @io.smallrye.config.WithName("admin.auth-provider")
    @io.smallrye.config.WithDefault("internal")
    String adminAuthProvider();
    @io.smallrye.config.WithName("admin.username")
    @io.smallrye.config.WithDefault("admin")
    String adminUsername();
    @io.smallrye.config.WithName("admin.password")
    @io.smallrye.config.WithDefault("admin-secret-2026$")
    String adminPassword();
    Superuser superuser();
    long timeoutMs();
    List<String> globalTopics();
    List<Route> auth();
    List<Route> acl();
    Storage storage();
    @io.smallrye.config.WithName("master-key")
    Optional<String> masterKey();
    @io.smallrye.config.WithName("monitoring")
    Optional<Monitoring> monitoring();
    @io.smallrye.config.WithName("telegram")
    Optional<Telegram> telegram();

    interface Monitoring {
        @io.smallrye.config.WithName("probe-interval-seconds")
        Optional<Integer> probeIntervalSeconds();
        @io.smallrye.config.WithName("probe-timeout-ms")
        Optional<Integer> probeTimeoutMs();
        @io.smallrye.config.WithName("failure-threshold")
        Optional<Integer> failureThreshold();
        @io.smallrye.config.WithName("recovery-threshold")
        Optional<Integer> recoveryThreshold();
    }

    interface Telegram {
        @io.smallrye.config.WithName("bot-token")
        Optional<String> botToken();

        @io.smallrye.config.WithName("api-url")
        @io.smallrye.config.WithDefault("https://api.telegram.org")
        String apiUrl();
    }

    interface Storage {
        @io.smallrye.config.WithName("legacy-api-key-env")
        Map<String, String> legacyApiKeyEnv();
    }

    interface Superuser {
        String username();
        String password();
    }
    interface Route {
        List<String> prefixes();
        URI url();
        @io.smallrye.config.WithName("api-key-env")
        Optional<String> apiKeyEnv();
        @Deprecated
        @io.smallrye.config.WithName("api-key")
        Optional<String> apiKey();
    }
}
