package pe.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.jboss.logging.Logger;

@Path("/api/v1/gateway")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class GatewayResource {
    private static final Logger LOG = Logger.getLogger(GatewayResource.class);
    @Inject GatewayConfig config;
    @Inject ObjectMapper json;
    @Inject GatewayState state;

    @POST @Path("/auth")
    public Map<String, Object> auth(String body) { return processAuth(body); }

    @POST @Path("/acl")
    public Map<String, Object> acl(String body) { return processAcl(body); }

    @POST @Path("/emqx/auth")
    public Map<String, Object> emqxAuth(String body) { return processAuth(body); }

    @POST @Path("/emqx/acl")
    public Map<String, Object> emqxAcl(String body) { return processAcl(body); }

    @POST @Path("/mosquitto/auth")
    public Response mosquittoAuth(String body, @HeaderParam("Content-Type") String type) {
        return mosquitto(processAuth(mosquittoBody(body, type, true)));
    }

    @POST @Path("/mosquitto/acl")
    public Response mosquittoAcl(String body, @HeaderParam("Content-Type") String type) {
        return mosquitto(processAcl(mosquittoBody(body, type, false)));
    }

    @POST @Path("/vernemq/auth")
    public Map<String, Object> vernemqAuth(String body) {
        return vernemq(processAuth(vernemqBody(body)));
    }

    @POST @Path("/vernemq/acl")
    public Map<String, Object> vernemqAcl(String body, @HeaderParam("vernemq-hook") String hook) {
        ObjectNode request = object(vernemqBody(body));
        if (request == null) return vernemq(processAcl("{}"));
        if (request.has("topics")) {
            JsonNode topics = request.get("topics");
            if (!topics.isArray() || topics.isEmpty()) return vernemq(processAcl("{}"));
            String aggregate = "allow";
            for (JsonNode topic : topics) {
                ObjectNode item = request.deepCopy();
                item.remove("topics");
                item.set("topic", topic.path("topic"));
                item.set("qos", topic.path("qos"));
                item.put("action", "subscribe");
                String decision = (String) processAcl(item.toString()).get("result");
                if ("deny".equals(decision)) aggregate = "deny";
                else if ("ignore".equals(decision) && !"deny".equals(aggregate)) aggregate = "ignore";
            }
            return vernemq(result(aggregate));
        }
        if (hook != null && !hook.equals("auth_on_publish") && !hook.equals("auth_on_publish_m5"))
            return vernemq(processAcl("{}"));
        request.put("action", "publish");
        return vernemq(processAcl(request.toString()));
    }

    @POST @Path("/generic/auth")
    public Map<String, Object> genericAuth(String body) { return generic(processAuth(body)); }

    @POST @Path("/generic/acl")
    public Map<String, Object> genericAcl(String body) { return generic(processAcl(body)); }

    private ObjectNode object(String body) {
        try {
            JsonNode value = json.readTree(body == null ? "" : body);
            return value instanceof ObjectNode node ? node : null;
        } catch (Exception e) { return null; }
    }

    private String mosquittoBody(String body, String type, boolean auth) {
        ObjectNode request;
        if (type != null && type.split(";", 2)[0].trim().equalsIgnoreCase(MediaType.APPLICATION_FORM_URLENCODED)) {
            request = json.createObjectNode();
            try {
                for (String pair : (body == null ? "" : body).split("&")) {
                    String[] parts = pair.split("=", 2);
                    request.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
                }
            } catch (IllegalArgumentException e) { return "{}"; }
        } else request = object(body);
        if (request == null) return "{}";
        if (!auth) {
            String action = switch (request.path("acc").asText()) {
                case "1", "4" -> "subscribe";
                case "2" -> "publish";
                default -> null;
            };
            if (action == null) return "{}";
            request.put("action", action);
        }
        return request.toString();
    }

    private String vernemqBody(String body) {
        ObjectNode request = object(body);
        if (request == null) return "{}";
        if (request.has("client_id")) request.set("clientid", request.get("client_id"));
        return request.toString();
    }

    private static Response mosquitto(Map<String, Object> decision) {
        int status = switch ((String) decision.get("result")) {
            case "allow" -> 200;
            case "ignore" -> 404;
            default -> 400;
        };
        return Response.status(status).entity(decision).build();
    }

    private static Map<String, Object> vernemq(Map<String, Object> decision) {
        return Map.of("result", switch ((String) decision.get("result")) {
            case "allow" -> "ok";
            case "ignore" -> "next";
            default -> Map.of("error", "not_allowed");
        });
    }

    private static Map<String, Object> generic(Map<String, Object> decision) {
        return Map.of("allowed", "allow".equals(decision.get("result")), "status", decision.get("result"));
    }

    private Map<String, Object> processAuth(String body) { return process(body, true); }
    private Map<String, Object> processAcl(String body) { return process(body, false); }

    private Map<String, Object> process(String body, boolean auth) {
        long start = System.nanoTime();
        JsonNode request = null;
        ObjectNode route = null;
        String result = "deny";
        boolean superuser = false;
        try {
            request = json.readTree(body == null ? "" : body);
            if (request == null || !request.isObject()) return result("deny");
            if (auth && config.superuser().username().equals(field(request, "username"))) {
                superuser = config.superuser().password().equals(field(request, "password"));
                result = superuser ? "allow" : "deny";
                return superuser ? Map.of("result", result, "is_superuser", true) : result(result);
            }
            String key = field(request, auth ? "clientid" : "topic");
            if (key == null || key.isBlank()) return result("deny");
            if (!auth && config.globalTopics().contains(key)) {
                result = "allow";
                return Map.of("result", result, "is_superuser", false);
            }
            route = state.match(key, auth);
            if (route == null) { result = "ignore"; return result(result); }
            long backendStart = System.nanoTime();
            String failure = null;
            HttpResponse<String> response;
            try {
                var builder = HttpRequest.newBuilder(URI.create(route.path(auth ? "authUrl" : "aclUrl").asText()))
                        .timeout(Duration.ofMillis(config.timeoutMs()))
                        .header("Content-Type", MediaType.APPLICATION_JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                // ponytail: la clave del backend solo se envía si está configurada.
                if (!route.path("apiKey").asText("").isEmpty())
                    builder.header("X-EMQX-API-KEY", route.path("apiKey").asText());
                response = state.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 500) failure = "HTTP " + response.statusCode();
            } catch (Exception e) {
                failure = e instanceof HttpTimeoutException ? "Timeout" : "Fallo de conexión";
                throw e;
            } finally {
                state.sample(route.path("id").asText(), (System.nanoTime() - backendStart) / 1_000_000.0);
                state.availability(route.path("id").asText(), failure, 0, false);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) return result("deny");
            JsonNode decision = json.readTree(response.body());
            String value = field(decision, "result");
            if ("allow".equals(value)) {
                result = "allow";
                return Map.of("result", result, "is_superuser",
                        decision.path("is_superuser").isBoolean() && decision.path("is_superuser").booleanValue());
            }
            if ("deny".equals(value) || "ignore".equals(value)) { result = value; return result(result); }
            return result("deny");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return result("deny");
        } catch (Exception e) {
            // ponytail: denegar ante fallos y nunca registrar cuerpos con credenciales.
            LOG.debugf("Solicitud del gateway denegada: %s", e.getClass().getSimpleName());
            return result("deny");
        } finally {
            state.audit(auth, request, result, route == null ? "" : route.path("name").asText(),
                    (System.nanoTime() - start) / 1_000_000.0);
        }
    }

    private static String field(JsonNode node, String name) {
        return node != null && node.path(name).isTextual() ? node.path(name).textValue() : null;
    }
    private static Map<String, Object> result(String value) { return Map.of("result", value); }
}
