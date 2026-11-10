package pe.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@jakarta.enterprise.context.ApplicationScoped
@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AdminResource {
    private final ConcurrentHashMap<String, Instant> tokens = new ConcurrentHashMap<>();
    @Context jakarta.ws.rs.core.HttpHeaders requestHeaders;
    @Inject GatewayState state;
    @Inject GatewayConfig config;
    @Inject ObjectMapper json;

    private WebApplicationException error(int status, String message) {
        return new WebApplicationException(Response.status(status).entity(Map.of("error", message)).build());
    }

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(AdminResource.class);
    private HttpResponse<String> emqx(String path, String authorization, JsonNode body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(config.emqxUrl() + "/api/v5/" + path))
                    .timeout(Duration.ofMillis(config.timeoutMs()));
            if (authorization != null) builder.header("Authorization", authorization);
            if (body != null) builder.header("Content-Type", MediaType.APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            return state.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOG.error("Solicitud EMQX fallida: " + path + " -> " + e.getMessage(), e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error(503, "EMQX no está disponible");
        }
    }

    private boolean internal() {
        return switch (config.adminAuthProvider()) {
            case "internal" -> true;
            case "emqx" -> false;
            default -> throw error(503, "Proveedor administrativo no admitido");
        };
    }

    // ponytail: una cuenta configurada y sesiones en memoria, sin otro almacén.
    private void require(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() < 8)
            throw error(401, "Se requiere un token Bearer");
        if (internal()) {
            String token = authorization.substring(7);
            Instant expires = tokens.get(token);
            if (expires == null || !expires.isAfter(Instant.now())) {
                if (expires != null) tokens.remove(token, expires);
                throw error(401, "Token inválido o caducado");
            }
            return;
        }
        int code = emqx("users", authorization, null).statusCode();
        if (code == 401 || code == 403) throw error(code, "Autorización EMQX rechazada");
        if (code < 200 || code >= 300) throw error(503, "Verificación EMQX no disponible");
    }

    @GET @Path("/auth/verify")
    public Map<String, Boolean> verify(@HeaderParam("Authorization") String authorization) {
        require(authorization);
        return Map.of("valid", true);
    }

    @POST @Path("/auth/verify")
    public Map<String, Boolean> verifyPost(@HeaderParam("Authorization") String authorization) {
        return verify(authorization);
    }

    @POST @Path("/auth/login") @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode login(JsonNode body) {
        String username = GatewayState.safe(body, "username"), password = GatewayState.safe(body, "password");
        if (username.isBlank() || password.isEmpty()) throw error(400, "Se requieren usuario y contraseña");
        if (internal()) {
            if (!config.adminUsername().equals(username) || !MessageDigest.isEqual(
                    config.adminPassword().getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8)))
                throw error(401, "Credenciales inválidas");
            Instant now = Instant.now();
            tokens.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
            String token = UUID.randomUUID().toString();
            tokens.put(token, now.plus(Duration.ofHours(8)));
            return json.createObjectNode().put("token", token).put("username", username);
        }
        var credentials = json.createObjectNode().put("username", username).put("password", password);
        var response = emqx("login", null, credentials);
        if (response.statusCode() == 401 || response.statusCode() == 403)
            throw error(401, "Credenciales EMQX inválidas");
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw error(503, "Inicio de sesión EMQX no disponible");
        try {
            JsonNode value = json.readTree(response.body());
            if (GatewayState.safe(value, "token").isEmpty()) throw new IllegalArgumentException();
            return json.createObjectNode().put("token", value.path("token").asText()).put("username", username);
        } catch (Exception e) { throw error(502, "Respuesta de inicio de sesión EMQX inválida"); }
    }

    @GET @Path("/routes")
    public JsonNode routes(@HeaderParam("Authorization") String auth) {
        require(auth); return state.routes(false);
    }

    @POST @Path("/routes") @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@HeaderParam("Authorization") String auth, ObjectNode body) {
        require(auth);
        if (body == null) throw error(400, "Se requiere una ruta");
        return Response.status(201).entity(state.put(null, body)).build();
    }

    @PUT @Path("/routes/{id}") @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode update(@HeaderParam("Authorization") String auth, @PathParam("id") String id, ObjectNode body) {
        require(auth);
        if (body == null) throw error(400, "Se requiere una ruta");
        return state.put(id, body);
    }

    @PUT @Path("/routes") @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode updateBody(@HeaderParam("Authorization") String auth, ObjectNode body) {
        return update(auth, GatewayState.safe(body, "id"), body);
    }

    private void confirmPassword(String password, String username) {
        // ponytail: conservar clientes automatizados antiguos; la UI siempre envía confirmación.
        if (password == null && !requestHeaders.getRequestHeaders().containsKey("X-Admin-Password")) return;
        if (password == null) password = "";
        boolean valid;
        if (internal()) {
            valid = MessageDigest.isEqual(config.adminPassword().getBytes(StandardCharsets.UTF_8),
                    password.getBytes(StandardCharsets.UTF_8));
        } else {
            String account = username == null || username.isBlank() ? config.adminUsername() : username;
            var credentials = json.createObjectNode().put("username", account).put("password", password);
            var response = emqx("login", null, credentials);
            if (response.statusCode() == 401 || response.statusCode() == 403) valid = false;
            else if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw error(503, "Inicio de sesión EMQX no disponible");
            else {
                try { valid = !GatewayState.safe(json.readTree(response.body()), "token").isEmpty(); }
                catch (Exception e) { throw error(502, "Respuesta de inicio de sesión EMQX inválida"); }
            }
        }
        if (!valid) throw error(403, "Contraseña de confirmación incorrecta");
    }

    @DELETE @Path("/routes/{id}")
    public Response delete(@HeaderParam("Authorization") String auth, @PathParam("id") String id,
            @HeaderParam("X-Admin-Password") String password, @HeaderParam("X-Admin-Username") String username) {
        require(auth);
        confirmPassword(password, username);
        state.delete(id);
        return Response.noContent().build();
    }

    @DELETE @Path("/routes")
    public Response deleteQuery(@HeaderParam("Authorization") String auth, @QueryParam("id") String id,
            @HeaderParam("X-Admin-Password") String password, @HeaderParam("X-Admin-Username") String username) {
        return delete(auth, id, password, username);
    }

    @POST @Path("/routes/{id}/test")
    public JsonNode test(@HeaderParam("Authorization") String auth, @PathParam("id") String id) {
        require(auth); return state.probe(id);
    }

    @GET @Path("/metrics")
    public JsonNode metrics(@HeaderParam("Authorization") String auth) {
        require(auth); return state.metrics();
    }

    @GET @Path("/incidents")
    public JsonNode incidents(@HeaderParam("Authorization") String auth) {
        require(auth); return state.incidents();
    }

    @GET @Path("/logs")
    public JsonNode logs(@HeaderParam("Authorization") String auth) {
        require(auth); return state.logs();
    }

    @DELETE @Path("/logs")
    public Response clearLogs(@HeaderParam("Authorization") String auth) {
        require(auth);
        state.clearLogs();
        return Response.noContent().build();
    }

    @GET @Path("/stream") @Produces(MediaType.SERVER_SENT_EVENTS)
    public io.smallrye.mutiny.Multi<String> stream(
            @HeaderParam("Authorization") String authHeader,
            @QueryParam("token") String tokenParam) {
        String auth = (authHeader != null && !authHeader.isBlank()) ? authHeader : (tokenParam != null ? "Bearer " + tokenParam : null);
        require(auth);
        return state.streamEvents();
    }
}
