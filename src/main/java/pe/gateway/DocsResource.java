package pe.gateway;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;

@Path("/")
public class DocsResource {

    private static final java.nio.file.Path DISK_DOCS = java.nio.file.Path.of("src/main/resources/META-INF/resources/gateway/docs.html");

    private Response serveHtml() {
        try {
            byte[] bytes = null;
            if (Files.isRegularFile(DISK_DOCS)) {
                bytes = Files.readAllBytes(DISK_DOCS);
            }
            if (bytes == null) {
                try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/resources/gateway/docs.html")) {
                    if (is != null) {
                        bytes = is.readAllBytes();
                    }
                }
            }
            if (bytes == null) {
                return Response.status(Response.Status.NOT_FOUND).entity("Docs not found").build();
            }
            CacheControl cc = new CacheControl();
            cc.setNoCache(true);
            cc.setNoStore(true);
            cc.setMustRevalidate(true);
            return Response.ok(bytes, "text/html; charset=UTF-8").cacheControl(cc).build();
        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    @GET
    @Path("/gateway/docs")
    @Produces(MediaType.TEXT_HTML)
    public Response getGatewayDocs() {
        return serveHtml();
    }

    @GET
    @Path("/docs")
    @Produces(MediaType.TEXT_HTML)
    public Response getRootDocs() {
        return serveHtml();
    }

    @GET
    @Path("/gateway/docs.html")
    public Response redirectHtml() {
        return Response.status(Response.Status.MOVED_PERMANENTLY).location(URI.create("/gateway/docs")).build();
    }
}
