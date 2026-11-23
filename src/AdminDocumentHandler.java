import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/** Local administration only. The token belongs in the environment, never a URL or web asset. */
final class AdminDocumentHandler implements HttpHandler {
    private static final String ORIGIN = "http://127.0.0.1:8080";
    private static final String ALLOW = "GET, POST, PUT, DELETE";
    private final DocumentStore store;
    private final byte[] tokenHash;

    AdminDocumentHandler(DocumentStore store, String token) {
        validateToken(token);
        this.store = store;
        tokenHash = digest(token);
    }

    // Use a randomly generated URL-safe token of at least 32 characters.
    static void validateToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{32,256}")) {
            throw new IllegalArgumentException("Managed mode requires a configured administrator token.");
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        try {
            if (!"/admin/documents".equals(exchange.getRequestURI().getRawPath())) {
                error(exchange, 404, "Unknown route.");
                return;
            }
            // A fixed Host also rejects DNS rebinding to this loopback listener.
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                    || !"127.0.0.1:8080".equals(singleHeader(exchange, "Host"))) {
                error(exchange, 403, "Local access required.");
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"document-admin\"");
                error(exchange, 401, "Administrator authorization required.");
                return;
            }
            List<String> origins = exchange.getRequestHeaders().get("Origin");
            if (origins != null && (origins.size() != 1 || !ORIGIN.equals(origins.getFirst()))) {
                error(exchange, 403, "Origin is not allowed.");
                return;
            }
            String method = exchange.getRequestMethod();
            if (!List.of("GET", "POST", "PUT", "DELETE").contains(method)) {
                exchange.getResponseHeaders().set("Allow", ALLOW);
                error(exchange, 405, "Method is not allowed.");
                return;
            }
            int responseCode = 200;
            String response;
            try (InputStream body = new BoundedBody(exchange.getRequestBody(), store.getMaxDocumentBytes())) {
                if ("GET".equals(method)) {
                    if (exchange.getRequestURI().getRawQuery() != null || body.read() != -1) {
                        throw new IllegalArgumentException();
                    }
                    response = "{\"documents\":[" + String.join(",", store.listDocumentNames().stream()
                            .map(SearchServer::jsonString).toList()) + "]}";
                } else {
                    String name = SearchServer.parseDocumentName(exchange.getRequestURI().getRawQuery());
                    if ("DELETE".equals(method)) {
                        if (body.read() != -1) { throw new IllegalArgumentException(); }
                        store.delete(name);
                    } else {
                        if (!supportedContentType(singleHeader(exchange, "Content-Type"))
                                || (exchange.getRequestHeaders().containsKey("Content-Encoding")
                                && !"identity".equalsIgnoreCase(singleHeader(exchange, "Content-Encoding")))) {
                            error(exchange, 415, "Use raw text/plain UTF-8 content.");
                            return;
                        }
                        checkContentLength(exchange);
                        if ("POST".equals(method)) { store.add(name, body); }
                        else { store.replace(name, body); }
                    }
                    String status = "POST".equals(method) ? "created" : "PUT".equals(method) ? "replaced" : "deleted";
                    responseCode = "POST".equals(method) ? 201 : 200;
                    response = "{\"status\":" + SearchServer.jsonString(status)
                            + ",\"filename\":" + SearchServer.jsonString(name) + "}";
                }
            } catch (BodyTooLarge oversized) {
                error(exchange, 413, "Document exceeds the byte limit.");
                return;
            } catch (MalformedBody malformed) {
                error(exchange, 400, "Invalid request body.");
                return;
            } catch (FileAlreadyExistsException duplicate) {
                error(exchange, 409, "Document name conflicts with an existing document.");
                return;
            } catch (NoSuchFileException missing) {
                error(exchange, 404, "Document not found.");
                return;
            } catch (IllegalArgumentException invalid) {
                error(exchange, 400, "Invalid document request.");
                return;
            } catch (IOException unavailable) {
                error(exchange, 503, "Managed storage is unavailable; reconciliation may be required.");
                return;
            } catch (RuntimeException unexpected) {
                error(exchange, 500, "Document operation failed.");
                return;
            }
            // A disconnected response must not be mistaken for a failed storage transaction.
            SearchServer.sendJson(exchange, responseCode, response);
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String value = singleHeader(exchange, "Authorization");
        if (value == null || value.length() < 7 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String candidate = value.substring(7);
        if (!candidate.matches("[A-Za-z0-9_-]{32,256}")) { return false; }
        // Equal-size SHA-256 digests keep comparison time independent of token length/content.
        return MessageDigest.isEqual(tokenHash, digest(candidate));
    }

    private static byte[] digest(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String singleHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values != null && values.size() == 1 ? values.getFirst() : null;
    }

    private static boolean supportedContentType(String value) {
        if (value == null) { return false; }
        String[] parts = value.toLowerCase(Locale.ROOT).split(";", -1);
        if (!"text/plain".equals(parts[0].trim()) || parts.length > 2) { return false; }
        return parts.length == 1 || parts[1].trim().matches("charset\\s*=\\s*(utf-8|\"utf-8\")");
    }

    private void checkContentLength(HttpExchange exchange) throws BodyTooLarge {
        if (!exchange.getRequestHeaders().containsKey("Content-Length")) { return; }
        String value = singleHeader(exchange, "Content-Length");
        if (value == null || !value.matches("[0-9]+")) { throw new IllegalArgumentException(); }
        try {
            if (Long.parseLong(value) > store.getMaxDocumentBytes()) { throw new BodyTooLarge(); }
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException(); }
    }

    private static void error(HttpExchange exchange, int code, String message) throws IOException {
        SearchServer.sendJson(exchange, code, "{\"error\":" + SearchServer.jsonString(message) + "}");
    }

    private static final class BodyTooLarge extends IOException { }
    private static final class MalformedBody extends IOException {
        MalformedBody(IOException cause) { super(cause); }
    }

    /** Check actual bytes as well as the untrusted Content-Length header. */
    private static final class BoundedBody extends FilterInputStream {
        private final int limit;
        private int consumed;
        BoundedBody(InputStream body, int limit) { super(body); this.limit = limit; }
        @Override public int read() throws IOException {
            int result;
            try { result = in.read(); } catch (IOException invalid) { throw new MalformedBody(invalid); }
            if (result != -1 && ++consumed > limit) { throw new BodyTooLarge(); }
            return result;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int result;
            try { result = in.read(bytes, offset, Math.min(length, limit - consumed + 1)); }
            catch (IOException invalid) { throw new MalformedBody(invalid); }
            if (result > 0 && (consumed += result) > limit) { throw new BodyTooLarge(); }
            return result;
        }
    }
}
