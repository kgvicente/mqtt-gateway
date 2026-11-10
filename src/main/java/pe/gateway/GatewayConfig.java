package pe.gateway;

import io.smallrye.config.ConfigMapping;
import java.net.URI;
import java.util.List;
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

    interface Superuser {
        String username();
        String password();
    }
    interface Route {
        List<String> prefixes();
        URI url();
        Optional<String> apiKey();
    }
}
