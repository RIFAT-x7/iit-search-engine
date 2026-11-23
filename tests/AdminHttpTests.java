import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Run through RunAdminHttpTests.ps1: all files and servers belong to that runner. */
public class AdminHttpTests {
    private static final String BASE = "http://127.0.0.1:8080";
    private static final String ROUTE = "/admin/documents";
    private static final String TOKEN = System.getenv("IIT_SEARCH_ADMIN_TOKEN");
    private static final Path ROOT = Path.of("data", "managed");
    private static final byte[] ORIGINAL = utf8("\ufeffalpha alpha obsolete old phrase\r\nবাংলা\n");
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static int passed;
    private static int failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (!Files.isRegularFile(Path.of(".admin-http-test-owned"))) {
            throw new IllegalStateException("Use the isolated administrative test runner.");
        }
        try (CLIENT) {
            run("Token configuration rejects missing, blank, short and unsafe values", () -> {
                for (String value : new String[] {null, "", " ", "short", "a".repeat(31),
                        "a".repeat(257), "a".repeat(32) + " ", "é".repeat(32)}) {
                    try { AdminDocumentHandler.validateToken(value); throw new AssertionError("Invalid token accepted"); }
                    catch (IllegalArgumentException expected) { }
                }
            });
            run("All demonstration modes keep public reads and reject administration", () -> {
                for (String[] mode : new String[][] {{}, {"--phrase-demo"}, {"--showcase"}}) {
                    try (var server = SearchServer.start(mode)) {
                        status(get("/health"), 200);
                        check(search("alpha", "keyword").statusCode() == 200, "Public search");
                        for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
                            status(request(method, ROUTE + "?name=a.txt", utf8("alpha"), true, "text/plain"), 404);
                        }
                    }
                }
                check(!Files.exists(ROOT), "Demo mode opened managed storage");
            });
            managedTests();
            run("Closing and reopening managed mode preserves files and releases locks", () -> {
                try (DocumentStore probe = new DocumentStore(ROOT)) {
                    check(probe.listDocumentNames().equals(List.of("b.txt", "persist.txt", "z.txt")), "Lock or persistent inventory");
                }
                try (var server = SearchServer.start(new String[] {"--managed"})) {
                    check(names(search("persistent", "keyword")).equals(List.of("persist.txt")), "Restart search");
                    check(Arrays.equals(get("/document/view?name=persist.txt").body(), utf8("persistent restart content")), "Restart bytes");
                    status(admin("DELETE", "persist.txt", null), 200);
                }
                try (DocumentStore probe = new DocumentStore(ROOT)) {
                    check(probe.listDocumentNames().equals(List.of("b.txt", "z.txt")), "Restart deletion");
                }
            });
            run("Bind failure releases the newly acquired store lock", () -> {
                HttpServer occupied = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
                occupied.start();
                try {
                    try { SearchServer.start(new String[] {"--managed"}); throw new AssertionError("Bind should fail"); }
                    catch (IOException expected) { }
                    try (DocumentStore probe = new DocumentStore(ROOT)) { check(!probe.listDocumentNames().isEmpty(), "Lock leaked"); }
                } finally { occupied.stop(0); }
            });
            run("Missing web assets fail startup without retaining the store lock", () -> {
                Path asset = Path.of("web", "app.js");
                Path saved = Path.of("web", "app.js.saved");
                Files.move(asset, saved);
                try {
                    try { SearchServer.start(new String[] {"--managed"}); throw new AssertionError("Missing asset accepted"); }
                    catch (IOException expected) { }
                    try (DocumentStore probe = new DocumentStore(ROOT)) { probe.listDocumentNames(); }
                } finally { Files.move(saved, asset); }
            });
        }
        System.out.println("Administrative HTTP: total passed: " + passed + "; total failed: " + failed);
        if (failed > 0) { System.exit(1); }
    }

    private static void managedTests() throws Exception {
        try (var server = SearchServer.start(new String[] {"--managed"})) {
            run("Managed startup serves an empty inventory and owns exactly one store", () -> {
                status(get("/health"), 200);
                check(text(request("GET", ROUTE, null, true, null)).equals("{\"documents\":[]}"), "Empty inventory");
                try { new DocumentStore(ROOT).close(); throw new AssertionError("Second store opened"); }
                catch (IOException expected) { }
            });
            run("Every administrative method requires a valid bearer token", () -> {
                for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
                    var response = request(method, ROUTE + (method.equals("GET") ? "" : "?name=a.txt"), utf8("alpha"), false, "text/plain");
                    status(response, 401);
                    check(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"), "Challenge");
                }
                for (String value : List.of("Bearer " + "x".repeat(32), "Basic " + TOKEN, "Bearer short")) {
                    status(custom("GET", ROUTE, null, "Authorization", value), 401);
                }
                status(custom("GET", ROUTE, null, "Cookie", "token=" + TOKEN), 401);
                status(request("GET", ROUTE + "?token=" + TOKEN, null, false, null), 401);
                check(raw("GET " + ROUTE + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nAuthorization: Bearer " + TOKEN
                        + "\r\nAuthorization: Bearer " + TOKEN + "\r\n\r\n").startsWith("HTTP/1.1 401"), "Duplicate authorization");
                check(raw("POST " + ROUTE + "?name=a.txt HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nContent-Length: 9999999\r\n\r\n")
                        .startsWith("HTTP/1.1 401"), "Authentication must precede reading an untrusted body");
            });
            run("Foreign, opaque and duplicate Origins are rejected before mutation", () -> {
                for (String origin : List.of("http://example.com", "null", "http://localhost:8080", BASE + "/")) {
                    status(custom("POST", ROUTE + "?name=a.txt", utf8("alpha"), "Authorization", "Bearer " + TOKEN,
                            "Content-Type", "text/plain", "Origin", origin), 403);
                }
                status(custom("GET", ROUTE, null, "Authorization", "Bearer " + TOKEN,
                        "Origin", BASE, "Origin", BASE), 403);
            });
            run("A foreign Host is forbidden even with valid credentials", () -> {
                check(raw("GET " + ROUTE + " HTTP/1.1\r\nHost: evil.example\r\nAuthorization: Bearer " + TOKEN
                        + "\r\n\r\n").startsWith("HTTP/1.1 403"), "Host guard");
            });
            run("Authorized local-origin listing is private JSON without CORS", () -> {
                var response = custom("GET", ROUTE, null, "Authorization", "Bearer " + TOKEN, "Origin", BASE);
                status(response, 200);
                check(response.headers().firstValue("Content-Type").orElse("").contains("application/json"), "JSON type");
                check(response.headers().firstValue("Cache-Control").orElse("").equals("no-store"), "Cache policy");
                check(response.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff"), "Sniffing");
                check(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "CORS enabled");
            });
            run("Upload completes index publication and preserves original UTF-8 bytes", () -> {
                var response = admin("POST", "a.txt", ORIGINAL);
                status(response, 201);
                check(text(response).equals("{\"status\":\"created\",\"filename\":\"a.txt\"}"), "Create schema");
                check(names(search("obsolete", "keyword")).equals(List.of("a.txt")), "Uploaded search");
                check(Arrays.equals(get("/document/view?name=a.txt").body(), ORIGINAL), "View bytes");
                check(Arrays.equals(get("/document/download?name=a.txt").body(), ORIGINAL), "Download bytes");
                check(Arrays.equals(Files.readAllBytes(ROOT.resolve("a.txt")), ORIGINAL), "Persistent bytes");
            });
            run("Multiple uploads produce deterministic public ranking and inventory", () -> {
                status(admin("POST", "z.txt", utf8("gamma delta")), 201);
                status(admin("POST", "b.txt", utf8("beta gamma alpha")), 201);
                check(text(request("GET", ROUTE, null, true, null)).equals("{\"documents\":[\"a.txt\",\"b.txt\",\"z.txt\"]}"), "Inventory order");
                check(names(search("alpha", "keyword")).equals(List.of("a.txt", "b.txt")), "Keyword ranking");
                check(names(search("old phrase", "phrase")).equals(List.of("a.txt")), "Initial phrase");
            });
            run("Top-K returns the exact ranked prefix and scores", () -> assertTopK("alpha gamma"));
            run("Duplicate and case-colliding additions return conflict", () -> {
                for (String name : List.of("a.txt", "A.TXT")) { status(admin("POST", name, utf8("corrupt")), 409); }
            });
            run("Missing replacement and deletion return not found", () -> {
                status(admin("PUT", "missing.txt", utf8("alpha")), 404);
                status(admin("DELETE", "missing.txt", null), 404);
            });
            run("Strict queries reject duplicate, unexpected and malformed parameters", () -> {
                status(request("POST", ROUTE, utf8("corrupt"), true, "text/plain"), 400);
                for (String query : List.of("", "name", "name=a.txt&name=b.txt", "name=a.txt&x=1", "x=1",
                        "name=", "name=%C3%28", "name=a.txt&", "name=a.txt=bad")) {
                    status(request("POST", ROUTE + "?" + query, utf8("corrupt"), true, "text/plain"), 400);
                }
                status(request("GET", ROUTE + "?name=a.txt", null, true, null), 400);
                for (String method : List.of("PUT", "DELETE")) {
                    status(request(method, ROUTE + "?name=a.txt&name=b.txt", null, true, "text/plain"), 400);
                }
                String malformed = raw("POST " + ROUTE + "?name=%ZZ HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nContent-Length: 0\r\n\r\n");
                check(malformed.startsWith("HTTP/1.1 400"), "Invalid percent escape");
            });
            run("Unsafe filenames cannot escape or create unsupported files", () -> {
                for (String name : List.of("../escape.txt", "x/y.txt", "C:\\a.txt", "CON.txt", "x.txt:ads", "x.pdf", "x.txt.", "x.txt ", "x\u0000.txt")) {
                    status(admin("POST", name, utf8("corrupt")), 400);
                }
            });
            run("Safe Unicode filenames round-trip through the administrative API", () -> {
                String name = "বাংলা notes.txt";
                status(admin("POST", name, utf8("unicode filename")), 201);
                check(names(search("unicode", "keyword")).equals(List.of(name)), "Unicode filename search");
                status(admin("DELETE", name, null), 200);
            });
            run("Malformed UTF-8 and NUL text are rejected without publication", () -> {
                status(admin("POST", "bad.txt", new byte[] {(byte) 0xc3, 0x28}), 400);
                status(admin("PUT", "a.txt", utf8("bad\u0000text")), 400);
            });
            run("Unsupported media types, charsets and encodings are rejected", () -> {
                for (String type : new String[] {null, "application/json", "multipart/form-data", "text/plain; charset=iso-8859-1", "text/plain; x=y"}) {
                    status(request("POST", ROUTE + "?name=bad.txt", utf8("corrupt"), true, type), 415);
                }
                status(custom("POST", ROUTE + "?name=bad.txt", utf8("corrupt"), "Authorization", "Bearer " + TOKEN,
                        "Content-Type", "text/plain", "Content-Encoding", "gzip"), 415);
            });
            run("Declared oversized bodies are rejected without waiting for their bytes", () -> {
                String response = raw("POST " + ROUTE + "?name=large.txt HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nAuthorization: Bearer "
                        + TOKEN + "\r\nContent-Type: text/plain\r\nContent-Length: " + (DocumentStore.DEFAULT_MAX_DOCUMENT_BYTES + 1) + "\r\n\r\n");
                check(response.startsWith("HTTP/1.1 413"), "Declared oversized body");
            });
            run("Chunked bodies are bounded without trusting Content-Length", () -> {
                byte[] oversized = new byte[DocumentStore.DEFAULT_MAX_DOCUMENT_BYTES + 1];
                Arrays.fill(oversized, (byte) 'x');
                var request = HttpRequest.newBuilder(URI.create(BASE + ROUTE + "?name=large.txt"))
                        .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized))).build();
                status(CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray()), 413);
            });
            run("Malformed body framing is rejected without touching storage", () -> {
                String response = raw("POST " + ROUTE + "?name=bad.txt HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nAuthorization: Bearer "
                        + TOKEN + "\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\nbad\r\n0\r\n\r\n");
                check(response.startsWith("HTTP/1.1 400"), "Malformed chunk framing");
                response = raw("PUT " + ROUTE + "?name=a.txt HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nAuthorization: Bearer "
                        + TOKEN + "\r\nContent-Type: text/plain\r\nContent-Length: 100\r\n\r\nshort");
                check(response.startsWith("HTTP/1.1 400"), "Truncated body with dishonest length");
            });
            run("The inclusive byte limit and empty documents are supported", () -> {
                byte[] limit = new byte[DocumentStore.DEFAULT_MAX_DOCUMENT_BYTES];
                Arrays.fill(limit, (byte) ' ');
                status(admin("POST", "limit.txt", limit), 201);
                check(Arrays.equals(get("/document/view?name=limit.txt").body(), limit), "Inclusive limit bytes");
                status(admin("DELETE", "limit.txt", null), 200);
                status(admin("POST", "empty.txt", new byte[0]), 201);
                var response = get("/document/view?name=empty.txt");
                status(response, 200);
                check(response.body().length == 0, "Empty view");
                status(admin("DELETE", "empty.txt", null), 200);
            });
            run("Exact route and method handling preserve Allow headers", () -> {
                var response = request("PATCH", ROUTE, null, true, null);
                status(response, 405);
                check(response.headers().firstValue("Allow").orElse("").equals("GET, POST, PUT, DELETE"), "Allow");
                status(request("OPTIONS", ROUTE, null, true, null), 405);
                for (String path : List.of(ROUTE + "/", ROUTE + "Extra", "/admin/reload", "/admin/%64ocuments")) {
                    status(request("GET", path, null, true, null), 404);
                }
            });
            run("Private staging files and arbitrary paths cannot be read over HTTP", () -> {
                for (String path : List.of("/data/managed/a.txt", "/data/managed/.iit-store/previous", "/.iit-store/lock")) {
                    status(get(path), 404);
                }
                status(get("/document/view?name=previous"), 404);
                status(get("/document/view?name=.iit-store%2Fprevious"), 400);
            });
            run("GET and DELETE bodies are rejected before mutation", () -> {
                status(request("GET", ROUTE, utf8("unexpected"), true, null), 400);
                status(admin("DELETE", "a.txt", utf8("unexpected")), 400);
            });
            run("All rejected operations preserve disk, bytes and searchable content", () -> {
                check(Arrays.equals(Files.readAllBytes(ROOT.resolve("a.txt")), ORIGINAL), "Disk changed");
                check(Arrays.equals(get("/document/view?name=a.txt").body(), ORIGINAL), "Indexed bytes changed");
                check(names(search("obsolete", "keyword")).equals(List.of("a.txt")), "Search changed");
                check(text(request("GET", ROUTE, null, true, null)).equals("{\"documents\":[\"a.txt\",\"b.txt\",\"z.txt\"]}"), "Unexpected file published");
                try (var files = Files.list(ROOT)) {
                    check(files.map(p -> p.getFileName().toString()).sorted().toList()
                            .equals(List.of(".iit-store", "a.txt", "b.txt", "z.txt")), "Rejected request wrote a file");
                }
            });
            run("Replacement removes obsolete keyword and phrase matches immediately", () -> {
                status(admin("PUT", "a.txt", utf8("renewed green campus alpha")), 200);
                check(names(search("obsolete", "keyword")).isEmpty(), "Old keyword");
                check(names(search("old phrase", "phrase")).isEmpty(), "Old phrase");
                check(names(search("green campus", "phrase")).equals(List.of("a.txt")), "New phrase");
                check(names(search("renewed AND alpha OR beta", "boolean")).equals(List.of("a.txt", "b.txt")), "New Boolean");
                check(Arrays.equals(get("/document/view?name=a.txt").body(), utf8("renewed green campus alpha")), "Replacement bytes");
                assertTopK("alpha gamma");
            });
            run("Storage conflicts return safe service errors and preserve published state", () -> {
                Path external = ROOT.resolve("external.txt");
                Files.writeString(external, "external content");
                try {
                    var response = admin("PUT", "a.txt", utf8("corrupt"));
                    status(response, 503);
                    String message = text(response);
                    check(!message.contains(ROOT.toAbsolutePath().toString()) && !message.contains("Exception")
                            && !message.contains(TOKEN) && !message.contains(".iit-store"), "Error leaked details");
                    check(names(search("renewed", "keyword")).equals(List.of("a.txt")), "Conflict changed index");
                } finally { Files.delete(external); }
            });
            run("Failed HTTP rebuild rolls back disk and keeps old search results", () -> {
                SearchEngine engine = ownedStore(server).getSearchEngine();
                setProcessor(engine, new TextProcessor() {
                    public List<String> tokenize(String text) {
                        if (text.equals("indexfailure")) { throw new IllegalStateException("Injected failure"); }
                        return super.tokenize(text);
                    }
                });
                try {
                    status(admin("PUT", "a.txt", utf8("indexfailure")), 503);
                    check(Files.readString(ROOT.resolve("a.txt")).equals("renewed green campus alpha"), "Failed rebuild disk");
                    check(names(search("renewed", "keyword")).equals(List.of("a.txt")), "Failed rebuild publication");
                } finally { setProcessor(engine, new TextProcessor()); }
            });
            run("Public HTTP searches overlap a paused rebuild and retain the old snapshot", () -> overlappingRequests(server));
            run("Concurrent HTTP mutations serialize without losing either update", () -> serializedRequests(server));
            run("Deletion removes keyword, phrase, Boolean and indexed byte access", () -> {
                status(admin("DELETE", "a.txt", null), 200);
                for (String mode : List.of("keyword", "phrase", "boolean")) {
                    check(names(search("parallel", mode)).isEmpty(), "Deleted " + mode);
                }
                status(get("/document/view?name=a.txt"), 404);
                status(get("/document/download?name=a.txt"), 404);
                check(!Files.exists(ROOT.resolve("a.txt")), "Deleted disk content");
                check(ownedStore(server).getSearchEngine().getPositionalPostings("parallel").isEmpty(), "Deleted positions");
            });
            run("Completed uploads are durable before the success response", () -> {
                status(admin("POST", "persist.txt", utf8("persistent restart content")), 201);
                check(Files.readString(ROOT.resolve("persist.txt")).equals("persistent restart content"), "Persistent upload");
            });
        }
    }

    private static void overlappingRequests(SearchServer.RunningServer server) throws Exception {
        SearchEngine engine = ownedStore(server).getSearchEngine();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        setProcessor(engine, new TextProcessor() {
            public List<String> tokenize(String text) {
                if (text.equals("parallel updated content")) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Writer gate timed out"); } }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
                return super.tokenize(text);
            }
        });
        var update = CLIENT.sendAsync(build("PUT", ROUTE + "?name=a.txt", utf8("parallel updated content"), true, "text/plain"),
                HttpResponse.BodyHandlers.ofByteArray());
        try {
            try {
                check(entered.await(5, TimeUnit.SECONDS), "Update never reached indexing");
                check(!update.isDone(), "Update already finished");
                // Completing this response before releasing the writer proves actual HTTP overlap.
                check(names(search("renewed", "keyword")).equals(List.of("a.txt")), "Search blocked or candidate leaked");
                check(Arrays.equals(get("/document/view?name=a.txt").body(), utf8("renewed green campus alpha")), "Mixed-generation bytes");
            } finally { release.countDown(); }
            status(update.get(8, TimeUnit.SECONDS), 200);
            check(names(search("parallel", "keyword")).equals(List.of("a.txt")), "Update unpublished");
            check(names(search("renewed", "keyword")).isEmpty(), "Old generation retained");
        } finally { release.countDown(); setProcessor(engine, new TextProcessor()); }
    }

    private static void serializedRequests(SearchServer.RunningServer server) throws Exception {
        DocumentStore store = ownedStore(server);
        SearchEngine engine = store.getSearchEngine();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        setProcessor(engine, new TextProcessor() {
            public List<String> tokenize(String text) {
                if (text.equals("serialized beta")) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Writer gate timed out"); } }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
                return super.tokenize(text);
            }
        });
        var first = CLIENT.sendAsync(build("PUT", ROUTE + "?name=b.txt", utf8("serialized beta"), true, "text/plain"), HttpResponse.BodyHandlers.ofByteArray());
        try {
            check(entered.await(5, TimeUnit.SECONDS), "First writer never reached rebuild");
            var second = CLIENT.sendAsync(build("POST", ROUTE + "?name=serial.txt", utf8("secondwriter"), true, "text/plain"), HttpResponse.BodyHandlers.ofByteArray());
            try {
                boolean blocked = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!blocked && System.nanoTime() < deadline) {
                    for (var thread : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
                        if (thread.getThreadState() == Thread.State.BLOCKED && thread.getLockInfo() != null
                                && thread.getLockInfo().getIdentityHashCode() == System.identityHashCode(store)) { blocked = true; }
                    }
                    Thread.yield();
                }
                check(blocked && !second.isDone(), "Second HTTP mutation did not wait on the store");
            } finally { release.countDown(); }
            status(first.get(8, TimeUnit.SECONDS), 200);
            status(second.get(8, TimeUnit.SECONDS), 201);
            check(names(search("serialized AND beta", "boolean")).equals(List.of("b.txt")), "First mutation lost");
            check(names(search("secondwriter", "keyword")).equals(List.of("serial.txt")), "Second mutation lost");
            status(admin("DELETE", "serial.txt", null), 200);
        } finally { release.countDown(); setProcessor(engine, new TextProcessor()); }
    }

    // Reflection is confined to tests: pause/fail the actual rebuild, never a substitute HTTP handler.
    private static DocumentStore ownedStore(SearchServer.RunningServer server) throws Exception {
        Field field = SearchServer.RunningServer.class.getDeclaredField("store");
        field.setAccessible(true);
        return (DocumentStore) field.get(server);
    }
    private static void setProcessor(SearchEngine engine, TextProcessor processor) throws Exception {
        Field field = SearchEngine.class.getDeclaredField("processor");
        field.setAccessible(true);
        field.set(engine, processor);
    }

    private static void assertTopK(String query) throws Exception {
        List<String> all = results(search(query, "keyword"));
        for (int k : new int[] {1, 2, 10}) {
            var response = get("/search?q=" + encode(query) + "&k=" + k);
            status(response, 200);
            check(results(response).equals(all.subList(0, Math.min(k, all.size()))), "Top-K score/order prefix");
        }
    }
    private static List<String> results(HttpResponse<byte[]> response) {
        return Pattern.compile("\\{\"filename\":.*?\\}").matcher(text(response)).results().map(m -> m.group()).toList();
    }
    private static List<String> names(HttpResponse<byte[]> response) {
        status(response, 200);
        return Pattern.compile("\"filename\":\"([^\"]*)\"").matcher(text(response)).results().map(m -> m.group(1)).toList();
    }
    private static HttpResponse<byte[]> search(String query, String mode) throws Exception {
        return get("/search?q=" + encode(query) + "&mode=" + mode);
    }
    private static HttpResponse<byte[]> get(String path) throws Exception { return request("GET", path, null, false, null); }
    private static HttpResponse<byte[]> admin(String method, String name, byte[] body) throws Exception {
        return request(method, ROUTE + "?name=" + encode(name), body, true, "text/plain; charset=utf-8");
    }
    private static HttpRequest build(String method, String path, byte[] body, boolean auth, String type) {
        var builder = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(4));
        if (auth) { builder.header("Authorization", "Bearer " + TOKEN); }
        if (type != null) { builder.header("Content-Type", type); }
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }
    private static HttpResponse<byte[]> request(String method, String path, byte[] body, boolean auth, String type) throws Exception {
        return CLIENT.send(build(method, path, body, auth, type), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static HttpResponse<byte[]> custom(String method, String path, byte[] body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(4));
        for (int i = 0; i < headers.length; i += 2) { builder.header(headers[i], headers[i + 1]); }
        return CLIENT.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static String raw(String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 8080), 2000);
            socket.setSoTimeout(3000);
            int boundary = request.indexOf("\r\n\r\n");
            String wire = request.substring(0, boundary) + "\r\nConnection: close" + request.substring(boundary);
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.shutdownOutput();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
    private static void status(HttpResponse<byte[]> response, int code) { check(response.statusCode() == code, "Expected HTTP " + code + ", got " + response.statusCode() + ": " + text(response)); }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void run(String name, Check test) {
        try { test.run(); passed++; System.out.println("PASS: " + name); }
        catch (Exception | AssertionError failure) {
            failed++;
            // Do not print exception objects: HTTP exceptions can include credential-bearing test URIs.
            System.out.println("FAIL: " + name + " (" + failure.getClass().getSimpleName() + ")");
            if (failure instanceof AssertionError) { System.out.println(failure.getMessage()); }
        }
    }
}
