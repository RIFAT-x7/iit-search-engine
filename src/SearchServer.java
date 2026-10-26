import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class SearchServer {
    public static void main(String[] args) throws IOException {
        SearchEngine engine = new SearchEngine();
        engine.buildIndex("data");
        System.out.println("Indexed " + engine.getDocumentCount() + " documents.");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/", exchange -> {
            try {
                // Check the exact path so /health/ and /healthcheck are not accepted.
                if (!"/health".equals(exchange.getRequestURI().getRawPath())) {
                    exchange.sendResponseHeaders(404, -1);
                } else if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    exchange.sendResponseHeaders(405, -1);
                } else {
                    byte[] response = "OK".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        System.out.println("Listening on http://127.0.0.1:8080");
    }
}
