package pe.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class StorageHealthCheck implements HealthCheck {
    @Inject
    StorageService storage;

    @Override
    public HealthCheckResponse call() {
        boolean healthy = storage != null && storage.isHealthy();
        return HealthCheckResponse.named("storage")
                .status(healthy)
                .withData("type", "sqlite")
                .withData("healthy", healthy)
                .build();
    }
}
