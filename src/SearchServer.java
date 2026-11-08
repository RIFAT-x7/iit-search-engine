import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SearchServer {
    public static void main(String[] args) throws IOException {
        String collection;
        if (args.length == 0) {
            collection = "data";
        } else if (args.length == 1 && "--phrase-demo".equals(args[0])) {
            collection = "data/phrase_samples";
        } else {
            System.err.println("Unsupported arguments. Usage: SearchServer [--phrase-demo]");
            System.exit(1);
            return;
        }
        // Run from the repository root so data and the fixed web files resolve correctly.
        SearchEngine engine = new SearchEngine();
        engine.buildIndex(collection);
        System.out.println("Collection: " + collection);
        System.out.println("Indexed " + engine.getDocumentCount() + " documents.");
        String indexHtml = Files.readString(Path.of("web", "index.html"), StandardCharsets.UTF_8);
        String appJs = Files.readString(Path.of("web", "app.js"), StandardCharsets.UTF_8);
        String stylesCss = Files.readString(Path.of("web", "styles.css"), StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getRawPath();
                boolean documentRoute = "/document/view".equals(path) || "/document/download".equals(path);
                // Context matching uses prefixes, so check each endpoint path exactly.
                if (!"/".equals(path) && !"/app.js".equals(path) && !"/styles.css".equals(path)
                        && !"/health".equals(path) && !"/search".equals(path) && !documentRoute) {
                    exchange.sendResponseHeaders(404, -1);
                } else if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    exchange.sendResponseHeaders(405, -1);
                } else if ("/".equals(path)) {
                    sendResponse(exchange, 200, indexHtml, "text/html; charset=utf-8");
                } else if ("/app.js".equals(path)) {
                    sendResponse(exchange, 200, appJs, "text/javascript; charset=utf-8");
                } else if ("/styles.css".equals(path)) {
                    sendResponse(exchange, 200, stylesCss, "text/css; charset=utf-8");
                } else if ("/health".equals(path)) {
                    sendText(exchange, 200, "OK");
                } else if (documentRoute) {
                    try {
                        String name = parseDocumentName(exchange.getRequestURI().getRawQuery());
                        byte[] bytes = engine.getIndexedDocumentBytes(name);
                        if (bytes == null) {
                            sendJson(exchange, 404, "{\"error\":\"Document not found.\"}");
                        } else {
                            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                            if ("/document/download".equals(path)) {
                                // Only an exact allowlist hit reaches header construction.
                                exchange.getResponseHeaders().set("Content-Disposition", attachmentFilename(name));
                            }
                            sendBytes(exchange, bytes, "text/plain; charset=utf-8");
                        }
                    } catch (IllegalArgumentException invalidName) {
                        sendJson(exchange, 400, "{\"error\":\"Bad request: Invalid document name.\"}");
                    }
                } else {
                    try {
                        SearchRequest request = parseQuery(exchange.getRequestURI().getRawQuery());
                        if ("phrase".equals(request.mode())) {
                            sendJson(exchange, 200, documentJson(request.query(), request.mode(),
                                    engine.searchPhrase(request.query())));
                        } else if ("boolean".equals(request.mode())) {
                            sendJson(exchange, 200, documentJson(request.query(), request.mode(),
                                    engine.searchBoolean(request.query())));
                        } else {
                            sendJson(exchange, 200, searchJson(request.query(),
                                    request.k() == null ? engine.search(request.query())
                                            : engine.searchTopK(request.query(), request.k())));
                        }
                    } catch (IllegalArgumentException invalidQuery) {
                        sendJson(exchange, 400, "{\"error\":"
                                + jsonString("Bad request: " + invalidQuery.getMessage()) + "}");
                        return;
                    }

                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        System.out.println("Listening on http://127.0.0.1:8080");
    }

    // A null limit preserves the existing full keyword search; no default Top-K.
    private record SearchRequest(String query, String mode, Integer k) { }

    private static String parseDocumentName(String rawQuery) {
        if (rawQuery == null) { throw new IllegalArgumentException(); }
        String name = null;
        for (String parameter : rawQuery.split("&", -1)) {
            int separator = parameter.indexOf('=');
            if (separator < 0 || !"name".equals(decodeParameter(parameter.substring(0, separator))) || name != null) {
                throw new IllegalArgumentException();
            }
            name = decodeParameter(parameter.substring(separator + 1));
        }
        if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException();
        }
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (Character.isISOControl(character) || character == '/' || character == '\\' || character == ':') {
                throw new IllegalArgumentException();
            }
        }
        return name;
    }

    private static String attachmentFilename(String name) {
        StringBuilder fallback = new StringBuilder();
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            fallback.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || " ._-".indexOf(c) >= 0 ? c : '_');
        }
        // RFC 5987: UTF-8 bytes, percent-encoded; this small unescaped subset is always safe.
        for (byte value : name.getBytes(StandardCharsets.UTF_8)) {
            int c = value & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static SearchRequest parseQuery(String rawQuery) {
        if (rawQuery == null) {
            throw new IllegalArgumentException("Missing q parameter.");
        }
        String query = null;
        String mode = null;
        String kValue = null;
        // Split before decoding so an encoded '&' remains part of a value.
        for (String parameter : rawQuery.split("&", -1)) {
            int separator = parameter.indexOf('=');
            if (separator < 0) {
                throw new IllegalArgumentException("Parameters must use name=value.");
            }
            String name = decodeParameter(parameter.substring(0, separator));
            String value = decodeParameter(parameter.substring(separator + 1));
            if ("q".equals(name)) {
                if (query != null) {
                    throw new IllegalArgumentException("Duplicate q parameter.");
                }
                query = value;
            } else if ("mode".equals(name)) {
                if (mode != null) {
                    throw new IllegalArgumentException("Duplicate mode parameter.");
                }
                mode = value;
            } else if ("k".equals(name)) {
                if (kValue != null) {
                    throw new IllegalArgumentException("Duplicate k parameter.");
                }
                kValue = value;
            }
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("A nonblank q parameter is required.");
        }
        if (mode == null) {
            mode = "keyword";
        } else if (!"keyword".equals(mode) && !"phrase".equals(mode) && !"boolean".equals(mode)) {
            throw new IllegalArgumentException("mode must be keyword or phrase or boolean.");
        }
        Integer k = null;
        if (kValue != null) {
            if (!"keyword".equals(mode)) {
                throw new IllegalArgumentException("k is supported only in keyword mode.");
            }
            k = parseK(kValue);
        }
        return new SearchRequest(query, mode, k);
    }

    private static int parseK(String value) {
        String message = "k must be a decimal integer from 1 to 2147483647.";
        if (value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        // Accept ASCII decimal digits only, after the existing URL decoding.
        // parseInt alone would also accept signs and some non-ASCII digits.
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                throw new IllegalArgumentException(message);
            }
        }
        try {
            int k = Integer.parseInt(value);
            if (k < 1) {
                throw new IllegalArgumentException(message);
            }
            return k;
        } catch (NumberFormatException outOfRange) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String decodeParameter(String encoded) {
        byte[] input = encoded.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        for (int i = 0; i < input.length; i++) {
            if (input[i] == '%') {
                if (i + 2 >= input.length) {
                    throw new IllegalArgumentException("Incomplete URL escape.");
                }
                int high = Character.digit((char) input[i + 1], 16);
                int low = Character.digit((char) input[i + 2], 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid URL escape.");
                }
                decoded.write(high * 16 + low);
                i += 2;
            } else {
                decoded.write(input[i] == '+' ? ' ' : input[i]);
            }
        }
        try {
            // Reject invalid UTF-8 instead of silently substituting replacement characters.
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
        } catch (CharacterCodingException invalidEncoding) {
            throw new IllegalArgumentException("Query parameters must use valid UTF-8.");
        }
    }

    private static String searchJson(String query, List<SearchResult> results) {
        StringBuilder response = new StringBuilder("{\"query\":");
        response.append(jsonString(query)).append(",\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                response.append(',');
            }
            SearchResult result = results.get(i);
            response.append("{\"filename\":")
                    .append(jsonString(result.getDocument().getFilename()))
                    .append(",\"score\":").append(result.getScore()).append('}');
        }
        return response.append("]}").toString();
    }

    // Phrase and Boolean retrieval both return filenames without ranking scores.
    private static String documentJson(String query, String mode, List<Document> documents) {
        StringBuilder response = new StringBuilder("{\"query\":");
        response.append(jsonString(query)).append(",\"mode\":")
                .append(jsonString(mode)).append(",\"results\":[");
        for (int i = 0; i < documents.size(); i++) {
            if (i > 0) {
                response.append(',');
            }
            response.append("{\"filename\":")
                    .append(jsonString(documents.get(i).getFilename())).append('}');
        }
        return response.append("]}").toString();
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u00")
                                .append(Character.forDigit(character >> 4, 16))
                                .append(Character.forDigit(character & 0xf, 16));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendResponse(exchange, status, json, "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        sendResponse(exchange, status, text, "text/plain; charset=utf-8");
    }

    private static void sendBytes(HttpExchange exchange, byte[] bytes, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // HttpServer uses response length 0 for chunking; -1 correctly represents an empty file.
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String text,
                                     String contentType) throws IOException {
        byte[] response = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
