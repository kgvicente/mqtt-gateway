package pe.gateway;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

@Provider
public class AdminErrors implements ExceptionMapper<BadRequestException>, ContainerResponseFilter {
    public Response toResponse(BadRequestException error) {
        return Response.status(400).entity(Map.of("error", error.getMessage())).build();
    }

    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        if (request.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/admin"))
            response.getHeaders().putSingle("Cache-Control", "no-store");
    }
}
