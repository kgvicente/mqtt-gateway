package pe.gateway;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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
    private static final java.nio.file.Path DISK_DOCS_MD = java.nio.file.Path.of("src/main/resources/META-INF/resources/gateway/docs.md");
    private static final java.nio.file.Path DISK_REF_MD = java.nio.file.Path.of("docs/reference.es.md");
    private static final java.nio.file.Path DISK_LLMS = java.nio.file.Path.of("src/main/resources/META-INF/resources/gateway/llms.txt");
    private static final java.nio.file.Path DISK_LLMS_FULL = java.nio.file.Path.of("src/main/resources/META-INF/resources/gateway/llms-full.txt");

    private Response serveResource(java.nio.file.Path diskPath, String resourcePath, String contentType) {
        try {
            byte[] bytes = null;
            if (diskPath != null && Files.isRegularFile(diskPath)) {
                bytes = Files.readAllBytes(diskPath);
            }
            if (bytes == null && resourcePath != null) {
                try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
                    if (is != null) {
                        bytes = is.readAllBytes();
                    }
                }
            }
            if (bytes == null) {
                return Response.status(Response.Status.NOT_FOUND).entity("Resource not found").build();
            }
            CacheControl cc = new CacheControl();
            cc.setNoCache(true);
            cc.setNoStore(true);
            cc.setMustRevalidate(true);
            return Response.ok(bytes, contentType).cacheControl(cc).build();
        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    private Response serveHtml() {
        return serveResource(DISK_DOCS, "META-INF/resources/gateway/docs.html", "text/html; charset=UTF-8");
    }

    private Response serveMarkdown() {
        byte[] bytes = null;
        if (Files.isRegularFile(DISK_DOCS_MD)) {
            try { bytes = Files.readAllBytes(DISK_DOCS_MD); } catch (Exception ignored) {}
        }
        if (bytes == null && Files.isRegularFile(DISK_REF_MD)) {
            try { bytes = Files.readAllBytes(DISK_REF_MD); } catch (Exception ignored) {}
        }
        if (bytes == null) {
            return serveResource(null, "META-INF/resources/gateway/docs.md", "text/markdown; charset=UTF-8");
        }
        CacheControl cc = new CacheControl();
        cc.setNoCache(true);
        cc.setNoStore(true);
        cc.setMustRevalidate(true);
        return Response.ok(bytes, "text/markdown; charset=UTF-8").cacheControl(cc).build();
    }

    private Response serveLlmsTxt() {
        return serveResource(DISK_LLMS, "META-INF/resources/gateway/llms.txt", "text/plain; charset=UTF-8");
    }

    private Response serveLlmsFullTxt() {
        return serveResource(DISK_LLMS_FULL, "META-INF/resources/gateway/llms-full.txt", "text/plain; charset=UTF-8");
    }

    @GET
    @Path("/gateway/docs")
    public Response getGatewayDocs(@HeaderParam("Accept") String acceptHeader) {
        if (acceptHeader != null && acceptHeader.contains("text/markdown")) {
            return serveMarkdown();
        }
        return serveHtml();
    }

    @GET
    @Path("/docs")
    public Response getRootDocs(@HeaderParam("Accept") String acceptHeader) {
        if (acceptHeader != null && acceptHeader.contains("text/markdown")) {
            return serveMarkdown();
        }
        return serveHtml();
    }

    @GET
    @Path("/gateway/docs.md")
    @Produces("text/markdown")
    public Response getGatewayDocsMd() {
        return serveMarkdown();
    }

    @GET
    @Path("/gateway/docs/index.md")
    @Produces("text/markdown")
    public Response getGatewayDocsIndexMd() {
        return serveMarkdown();
    }

    @GET
    @Path("/docs.md")
    @Produces("text/markdown")
    public Response getRootDocsMd() {
        return serveMarkdown();
    }

    @GET
    @Path("/docs/index.md")
    @Produces("text/markdown")
    public Response getRootDocsIndexMd() {
        return serveMarkdown();
    }

    @GET
    @Path("/gateway/llms.txt")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getGatewayLlmsTxt() {
        return serveLlmsTxt();
    }

    @GET
    @Path("/llms.txt")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getRootLlmsTxt() {
        return serveLlmsTxt();
    }

    @GET
    @Path("/gateway/llms-full.txt")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getGatewayLlmsFullTxt() {
        return serveLlmsFullTxt();
    }

    @GET
    @Path("/llms-full.txt")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getRootLlmsFullTxt() {
        return serveLlmsFullTxt();
    }

    @GET
    @Path("/gateway/docs.html")
    public Response redirectHtml() {
        return Response.status(Response.Status.MOVED_PERMANENTLY).location(URI.create("/gateway/docs")).build();
    }
}
