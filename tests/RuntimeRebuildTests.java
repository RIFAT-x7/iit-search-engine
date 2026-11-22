import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Full rebuilds only. Thread gates force publication during individual queries. */
public class RuntimeRebuildTests {
    private static int passed;
    private static int failed;
    private static final String QUERY = "alpha beta gamma";
    private static final String BOOLEAN = "alpha AND beta OR gamma";

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    private static void run(String name, Check test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS: " + name);
        } catch (Exception | AssertionError error) {
            failed++;
            System.out.println("FAIL: " + name + " - " + error);
            error.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    public static void main(String[] args) throws Exception {
        run("Empty initial engine and failed first build", RuntimeRebuildTests::emptyEngine);
        run("Successful rebuild adds new searchable content", RuntimeRebuildTests::addition);
        run("Replacement removes old terms and refreshes bytes", RuntimeRebuildTests::replacement);
        run("Deleted documents disappear and IDs remain contiguous", RuntimeRebuildTests::deletion);
        run("Phrase and Boolean semantics survive rebuild", RuntimeRebuildTests::phraseAndBoolean);
        run("Top-K remains the exact full-search prefix including score bits", RuntimeRebuildTests::topK);
        run("Original bytes remain exact and defensive across rebuilds", RuntimeRebuildTests::bytes);
        run("Statistics and retained positional snapshots remain correct", RuntimeRebuildTests::statistics);
        run("Invalid UTF-8 preserves the entire previous generation", RuntimeRebuildTests::invalidUtf8);
        run("Missing and non-directory collections preserve the previous generation", RuntimeRebuildTests::invalidCollections);
        run("Indexing exceptions preserve the previous generation", RuntimeRebuildTests::indexingFailure);
        run("Successful empty rebuild clears all prior state", RuntimeRebuildTests::emptyRebuild);
        run("Readers complete while a rebuild is paused before publication", RuntimeRebuildTests::readersDuringBuild);
        run("Overlapping rebuild writers serialize before scanning", RuntimeRebuildTests::overlappingWriters);
        run("Keyword query retains one generation during publication", () -> duringQuery(e -> ranked(e.search(QUERY)), false));
        run("Top-K query retains one generation during publication", () -> duringQuery(e -> ranked(e.searchTopK(QUERY, 2)), false));
        run("Phrase query retains one generation during publication", () -> duringQuery(e -> names(e.searchPhrase("alpha beta")), true));
        run("Boolean operands and metadata retain one generation during publication", () -> duringQuery(e -> names(e.searchBoolean(BOOLEAN)), false));
        run("Concurrent readers accept only complete individual-call results", RuntimeRebuildTests::concurrentReaders);
        System.out.println("Runtime rebuild: total passed: " + passed + "; total failed: " + failed);
        if (failed > 0) { System.exit(1); }
    }

    private static void emptyEngine() throws Exception {
        SearchEngine engine = new SearchEngine();
        check(engine.getDocumentCount() == 0 && engine.getUniqueTermCount() == 0
                && engine.getTotalTokens() == 0, "Initial statistics");
        check(engine.search(QUERY).isEmpty() && engine.searchTopK(QUERY, 2).isEmpty()
                && engine.searchPhrase("alpha beta").isEmpty() && engine.searchBoolean(BOOLEAN).isEmpty(), "Initial results");
        check(engine.getIndexedDocumentBytes(null) == null && engine.getPositionalPostings("alpha").isEmpty(), "Initial snapshots");
        try (Fixture f = oldCollection()) {
            expectIo(() -> engine.buildIndex(f.folder.resolve("missing").toString()));
            check(engine.getDocumentCount() == 0, "Failed first build published state");
            engine.buildIndex(f.folder.toString());
            check(engine.getDocumentCount() == 2, "Engine unusable after failed first build");
        }
    }

    private static void addition() throws Exception {
        try (Fixture f = oldCollection()) {
            f.write("c.txt", "delta alpha");
            check(f.engine.search("delta").isEmpty(), "Changed without rebuild");
            f.build();
            check(names(f.engine.searchBoolean("delta")).equals(List.of("3:c.txt")), "Added document");
            check(f.engine.getDocumentCount() == 3, "Added count");
        }
    }

    private static void replacement() throws Exception {
        try (Fixture f = oldCollection()) {
            f.write("a.txt", "delta delta");
            f.build();
            check(f.engine.search("beta").isEmpty(), "Old replacement posting retained");
            check(names(f.engine.searchBoolean("delta")).equals(List.of("1:a.txt")), "Replacement missing");
            check(Arrays.equals(utf8("delta delta"), f.engine.getIndexedDocumentBytes("a.txt")), "Replacement bytes");
        }
    }

    private static void deletion() throws Exception {
        try (Fixture f = oldCollection()) {
            Files.delete(f.folder.resolve("a.txt"));
            f.build();
            check(f.engine.getIndexedDocumentBytes("a.txt") == null && f.engine.search("beta").isEmpty(), "Deleted content retained");
            check(names(f.engine.searchBoolean("gamma")).equals(List.of("1:b.txt")), "IDs not rebuilt contiguously");
            check(ranked(f.engine.searchTopK("gamma", 1)).equals(ranked(f.engine.search("gamma"))), "Top-K after deletion");
        }
    }

    private static void phraseAndBoolean() throws Exception {
        try (Fixture f = oldCollection()) {
            f.write("a.txt", "alpha and beta alpha beta");
            f.build();
            check(names(f.engine.searchPhrase("alpha and beta")).equals(List.of("1:a.txt")), "Stop-word phrase");
            check(names(f.engine.searchPhrase("beta alpha")).equals(List.of("1:a.txt")), "Changed phrase");
            check(names(f.engine.searchBoolean("alpha AND beta OR gamma")).equals(List.of("1:a.txt", "2:b.txt")), "Boolean groups");
        }
    }

    private static void topK() throws Exception {
        try (Fixture f = oldCollection()) {
            for (int round = 0; round < 2; round++) {
                for (String query : List.of(QUERY, "gamma alpha alpha", "unknown", "the and")) {
                    var all = f.engine.search(query);
                    for (int k : new int[] {1, 2, 5, Integer.MAX_VALUE}) {
                        check(ranked(f.engine.searchTopK(query, k)).equals(ranked(all.subList(0, Math.min(k, all.size())))), "Top-K prefix");
                    }
                }
                f.write("a.txt", "gamma gamma alpha");
                f.build();
            }
        }
    }

    private static void bytes() throws Exception {
        try (Fixture f = oldCollection()) {
            byte[] original = utf8("\ufeffalpha\r\nBangla: \u09ac\u09be\u0982\u09b2\u09be\n");
            f.writeBytes("a.txt", original);
            f.build();
            byte[] returned = f.engine.getIndexedDocumentBytes("a.txt");
            check(Arrays.equals(original, returned), "Original bytes altered");
            returned[0] ^= 1;
            check(Arrays.equals(original, f.engine.getIndexedDocumentBytes("a.txt")), "Mutable byte exposure");
            f.write("a.txt", "delta");
            check(Arrays.equals(original, f.engine.getIndexedDocumentBytes("a.txt")), "Source mutation leaked");
            f.build();
            check(Arrays.equals(utf8("delta"), f.engine.getIndexedDocumentBytes("a.txt")), "Rebuilt bytes stale");
            check(f.engine.getIndexedDocumentBytes(null) == null, "Null-name compatibility");
        }
    }

    private static void statistics() throws Exception {
        try (Fixture f = oldCollection()) {
            check(f.engine.getDocumentCount() == 2 && f.engine.getUniqueTermCount() == 3
                    && f.engine.getTotalTokens() == 5, "Initial statistics");
            Map<Integer, List<Integer>> retained = f.engine.getPositionalPostings("alpha");
            check(retained.equals(Map.of(1, List.of(0), 2, List.of(1))), "Initial positions");
            try { retained.put(3, List.of(0)); throw new AssertionError("Mutable map"); }
            catch (UnsupportedOperationException expected) { }
            try { retained.get(1).add(10); throw new AssertionError("Mutable positions"); }
            catch (UnsupportedOperationException expected) { }
            f.write("a.txt", "the delta delta epsilon");
            f.build();
            check(f.engine.getDocumentCount() == 2 && f.engine.getUniqueTermCount() == 4
                    && f.engine.getTotalTokens() == 6, "Rebuilt statistics");
            check(f.engine.getPositionalPostings("delta").equals(Map.of(1, List.of(1, 2))), "Stop-word gap");
            check(retained.equals(Map.of(1, List.of(0), 2, List.of(1))), "Old positional snapshot changed");
        }
    }

    // Calls are compared across time only when no writer can publish between them.
    private static List<Object> fingerprint(SearchEngine engine) {
        byte[] bytes = engine.getIndexedDocumentBytes("a.txt");
        return List.of(ranked(engine.search(QUERY)), ranked(engine.searchTopK(QUERY, 2)),
                names(engine.searchPhrase("alpha beta")), names(engine.searchBoolean(BOOLEAN)),
                engine.getDocumentCount(), engine.getUniqueTermCount(), engine.getTotalTokens(),
                engine.getPositionalPostings("alpha"), bytes == null ? "missing" : Arrays.toString(bytes));
    }

    private static void invalidUtf8() throws Exception {
        try (Fixture f = oldCollection()) {
            var before = fingerprint(f.engine);
            f.write("a.txt", "replacement");
            f.writeBytes("z.txt", new byte[] {(byte) 0xc3, 0x28});
            expectIo(f::build);
            check(fingerprint(f.engine).equals(before), "Failed build changed generation");
            check(f.engine.getIndexedDocumentBytes("z.txt") == null, "Partial bytes published");
            Files.delete(f.folder.resolve("z.txt"));
            f.build();
            check(!fingerprint(f.engine).equals(before), "Cannot recover after failed build");
        }
    }

    private static void invalidCollections() throws Exception {
        try (Fixture f = oldCollection()) {
            var before = fingerprint(f.engine);
            for (Path invalid : List.of(f.folder.resolve("missing"), f.folder.resolve("a.txt"))) {
                expectIo(() -> f.engine.buildIndex(invalid.toString()));
                check(fingerprint(f.engine).equals(before), "Invalid collection changed generation");
            }
        }
    }

    private static void indexingFailure() throws Exception {
        try (Fixture f = oldCollection()) {
            var before = fingerprint(f.engine);
            setProcessor(f.engine, new TextProcessor() {
                public List<String> tokenize(String text) {
                    if (text.equals("indexfailure")) { throw new IllegalStateException("Injected indexing failure"); }
                    return super.tokenize(text);
                }
            });
            f.write("z.txt", "indexfailure");
            try { f.build(); throw new AssertionError("Indexing failure not raised"); }
            catch (IllegalStateException expected) { }
            check(fingerprint(f.engine).equals(before), "Indexing failure changed generation");
        }
    }

    private static void emptyRebuild() throws Exception {
        try (Fixture f = oldCollection(); Fixture empty = new Fixture()) {
            f.engine.buildIndex(empty.folder.toString());
            check(f.engine.getDocumentCount() == 0 && f.engine.getUniqueTermCount() == 0
                    && f.engine.getTotalTokens() == 0, "Empty statistics");
            check(f.engine.search(QUERY).isEmpty() && f.engine.searchTopK(QUERY, 2).isEmpty()
                    && f.engine.searchPhrase("alpha beta").isEmpty() && f.engine.searchBoolean(BOOLEAN).isEmpty(), "Empty results");
            check(f.engine.getIndexedDocumentBytes("a.txt") == null && f.engine.getPositionalPostings("alpha").isEmpty(), "Old snapshots retained");
        }
    }

    private static void readersDuringBuild() throws Exception {
        try (Fixture old = oldCollection(); Fixture next = new Fixture()) {
            next.write("next.txt", "paused alpha beta");
            var before = fingerprint(old.engine);
            Gate gate = new Gate();
            setProcessor(old.engine, new TextProcessor() {
                public List<String> tokenize(String text) {
                    if (text.startsWith("paused ")) { gate.pause(); }
                    return super.tokenize(text);
                }
            });
            try (Task<Void> writer = new Task<>(() -> { old.engine.buildIndex(next.folder.toString()); return null; })) {
                try {
                    gate.awaitEntry();
                    try (Task<List<Object>> reader = new Task<>(() -> fingerprint(old.engine))) {
                        check(reader.result().equals(before), "Reader blocked or saw candidate state");
                    }
                } finally { gate.release(); }
                writer.result();
            }
            check(names(old.engine.searchPhrase("alpha beta")).equals(List.of("1:next.txt")), "Candidate not published");
        }
    }

    private static void overlappingWriters() throws Exception {
        try (Fixture old = oldCollection(); Fixture first = new Fixture(); Fixture second = new Fixture()) {
            first.write("first.txt", "paused alpha");
            second.write("second.txt", "beta beta");
            Gate gate = new Gate();
            setProcessor(old.engine, new TextProcessor() {
                public List<String> tokenize(String text) {
                    if (text.startsWith("paused ")) { gate.pause(); }
                    return super.tokenize(text);
                }
            });
            try (Task<Void> a = new Task<>(() -> { old.engine.buildIndex(first.folder.toString()); return null; })) {
                try {
                    gate.awaitEntry();
                    try (Task<Void> b = new Task<>(() -> { old.engine.buildIndex(second.folder.toString()); return null; })) {
                        try { awaitWriterBlocked(b.thread, a.thread); }
                        finally { gate.release(); }
                        a.result();
                        b.result();
                    }
                } finally { gate.release(); }
            }
            check(names(old.engine.searchBoolean("beta")).equals(List.of("1:second.txt")), "Later writer lost");
            check(old.engine.getIndexedDocumentBytes("first.txt") == null, "Earlier bytes retained");
        }
    }

    private static void awaitWriterBlocked(Thread waiting, Thread owner) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiting.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == owner.threadId()) { return; }
            check(waiting.isAlive(), "Second writer completed before the first writer was released");
            Thread.yield();
        }
        throw new AssertionError("Second writer did not wait on the first writer's monitor");
    }

    private static void duringQuery(Function<SearchEngine, List<String>> query, boolean positional) throws Exception {
        try (Fixture old = oldCollection(); Fixture next = newCollection()) {
            List<String> expected = query.apply(old.engine);
            check(!expected.equals(query.apply(next.engine)), "Fixture generations must differ");
            Gate gate = new Gate();
            InvertedIndex original = (InvertedIndex) field(state(old.engine), "index");
            // Test-only decorated generation: pause its first posting lookup,
            // publish another real build, then require the whole query to finish old.
            installIndex(old.engine, new InvertedIndex() {
                private boolean first = true;
                private void pauseOnce(boolean positions) {
                    if (first && positions == positional) { first = false; gate.pause(); }
                }
                public Map<Integer, Integer> getPostings(String term) {
                    pauseOnce(false);
                    return original.getPostings(term);
                }
                public Map<Integer, List<Integer>> getPositionalPostings(String term) {
                    pauseOnce(true);
                    return original.getPositionalPostings(term);
                }
                public int getUniqueTermCount() { return original.getUniqueTermCount(); }
                public int getTotalTokens() { return original.getTotalTokens(); }
            });
            try (Task<List<String>> reader = new Task<>(() -> query.apply(old.engine))) {
                try {
                    gate.awaitEntry();
                    old.engine.buildIndex(next.folder.toString());
                } finally { gate.release(); }
                check(reader.result().equals(expected), "Mixed generation within retrieval");
            }
            check(query.apply(old.engine).equals(query.apply(next.engine)), "Later query missed publication");
        }
    }

    private static void concurrentReaders() throws Exception {
        try (Fixture old = oldCollection(); Fixture next = newCollection()) {
            List<Function<SearchEngine, Object>> calls = List.of(
                    e -> ranked(e.search(QUERY)), e -> ranked(e.searchTopK(QUERY, 2)),
                    e -> names(e.searchPhrase("alpha beta")), e -> names(e.searchBoolean(BOOLEAN)),
                    SearchEngine::getDocumentCount, SearchEngine::getUniqueTermCount, SearchEngine::getTotalTokens,
                    e -> e.getPositionalPostings("alpha"), e -> Arrays.toString(e.getIndexedDocumentBytes("a.txt")));
            List<Object> before = calls.stream().map(call -> call.apply(old.engine)).toList();
            List<Object> after = calls.stream().map(call -> call.apply(next.engine)).toList();
            CountDownLatch start = new CountDownLatch(1);
            List<Task<Void>> readers = new ArrayList<>();
            try (Task<Void> writer = new Task<>(() -> {
                await(start);
                for (int i = 0; i < 40; i++) {
                    old.engine.buildIndex((i % 2 == 0 ? next.folder : old.folder).toString());
                }
                return null;
            })) {
                try {
                    for (int reader = 0; reader < 4; reader++) {
                        readers.add(new Task<>(() -> {
                            await(start);
                            for (int round = 0; round < 200; round++) {
                                for (int i = 0; i < calls.size(); i++) {
                                    Object actual = calls.get(i).apply(old.engine);
                                    check(actual.equals(before.get(i)) || actual.equals(after.get(i)), "Partial individual-call result at " + i);
                                }
                            }
                            return null;
                        }));
                    }
                    start.countDown();
                    writer.result();
                    for (Task<Void> reader : readers) { reader.result(); }
                } finally {
                    start.countDown();
                    for (Task<Void> reader : readers) { reader.close(); }
                }
            }
        }
    }

    private static Object state(SearchEngine engine) throws Exception { return field(engine, "state"); }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void installIndex(SearchEngine engine, InvertedIndex index) throws Exception {
        Object old = state(engine);
        Constructor<?> constructor = old.getClass().getDeclaredConstructor(List.class, InvertedIndex.class, Map.class);
        constructor.setAccessible(true);
        Object decorated = constructor.newInstance(field(old, "documents"), index, field(old, "indexedDocuments"));
        Field stateField = SearchEngine.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(engine, decorated);
    }

    // Install before starting threads; production code has no test hooks.
    private static void setProcessor(SearchEngine engine, TextProcessor processor) throws Exception {
        Field field = SearchEngine.class.getDeclaredField("processor");
        field.setAccessible(true);
        field.set(engine, processor);
    }

    private static List<String> ranked(List<SearchResult> results) {
        return results.stream().map(r -> r.getDocument().getId() + ":" + r.getDocument().getFilename()
                + ":" + Double.doubleToLongBits(r.getScore())).toList();
    }

    private static List<String> names(List<Document> documents) {
        return documents.stream().map(d -> d.getId() + ":" + d.getFilename()).toList();
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static void expectIo(Check operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected IOException"); }
        catch (IOException expected) { }
    }

    private static void await(CountDownLatch latch) {
        try { check(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for thread gate"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        void pause() { entered.countDown(); await(released); }
        void awaitEntry() { await(entered); }
        void release() { released.countDown(); }
    }

    private static final class Task<T> implements AutoCloseable {
        private final FutureTask<T> future;
        private final Thread thread;
        Task(Callable<T> work) {
            future = new FutureTask<>(work);
            thread = new Thread(future, "runtime-rebuild-test");
            thread.setDaemon(true);
            thread.start();
        }
        T result() throws Exception { return future.get(10, TimeUnit.SECONDS); }
        public void close() throws Exception {
            thread.join(10000);
            if (thread.isAlive()) { thread.interrupt(); thread.join(1000); }
            check(!thread.isAlive(), "Test thread did not terminate");
        }
    }

    private static Fixture oldCollection() throws IOException {
        Fixture f = new Fixture();
        try { f.write("a.txt", "alpha beta"); f.write("b.txt", "gamma alpha gamma"); f.build(); return f; }
        catch (IOException | RuntimeException | Error failure) { f.close(); throw failure; }
    }

    private static Fixture newCollection() throws IOException {
        Fixture f = new Fixture();
        try {
            f.write("a.txt", "gamma gamma gamma"); f.write("c.txt", "alpha beta beta"); f.write("d.txt", "alpha beta delta");
            f.build(); return f;
        } catch (IOException | RuntimeException | Error failure) { f.close(); throw failure; }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path folder = Files.createTempDirectory("iit-runtime-rebuild-");
        private final List<Path> owned = new ArrayList<>();
        private final SearchEngine engine = new SearchEngine();
        Fixture() throws IOException { }
        void write(String name, String text) throws IOException { writeBytes(name, utf8(text)); }
        void writeBytes(String name, byte[] bytes) throws IOException {
            Path file = folder.resolve(name);
            if (!owned.contains(file)) { owned.add(file); }
            Files.write(file, bytes);
        }
        void build() throws IOException { engine.buildIndex(folder.toString()); }
        public void close() throws IOException {
            for (Path file : owned) { Files.deleteIfExists(file); }
            Files.delete(folder);
        }
    }
}
