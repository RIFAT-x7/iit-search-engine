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
                // Context matching uses prefixes, so check each endpoint path exactly.
                if (!"/".equals(path) && !"/app.js".equals(path) && !"/styles.css".equals(path)
                        && !"/health".equals(path) && !"/search".equals(path)) {
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
                } else {
                    SearchRequest request;
                    try {
                        request = parseQuery(exchange.getRequestURI().getRawQuery());
                    } catch (IllegalArgumentException invalidQuery) {
                        sendJson(exchange, 400, "{\"error\":"
                                + jsonString("Bad request: " + invalidQuery.getMessage()) + "}");
                        return;
                    }

                    if ("phrase".equals(request.mode())) {
                        sendJson(exchange, 200, phraseJson(request.query(),
                                engine.searchPhrase(request.query())));
                    } else {
                        sendJson(exchange, 200, searchJson(request.query(),
                                engine.search(request.query())));
                    }
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        System.out.println("Listening on http://127.0.0.1:8080");
    }

    private record SearchRequest(String query, String mode) { }

    private static SearchRequest parseQuery(String rawQuery) {
        if (rawQuery == null) {
            throw new IllegalArgumentException("Missing q parameter.");
        }
        String query = null;
        String mode = null;
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
            }
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("A nonblank q parameter is required.");
        }
        if (mode == null) {
            mode = "keyword";
        } else if (!"keyword".equals(mode) && !"phrase".equals(mode)) {
            throw new IllegalArgumentException("mode must be keyword or phrase.");
        }
        return new SearchRequest(query, mode);
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

    private static String phraseJson(String query, List<Document> documents) {
        StringBuilder response = new StringBuilder("{\"query\":");
        response.append(jsonString(query)).append(",\"mode\":\"phrase\",\"results\":[");
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
