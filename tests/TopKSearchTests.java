import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class TopKSearchTests {
    private static int passed;
    private static int failed;
    private static volatile double benchmarkSink;

    // Compile with the other suites. Normal invocation runs correctness tests.
    // Optional: java -cp out/app TopKSearchTests --benchmark
    // The benchmark uses only owned temporary files; no wall-clock assertions.
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--benchmark")) {
            benchmark();
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("Usage: TopKSearchTests [--benchmark]");
        }
        run("Top 1", () -> testLimit(1));
        run("Top 2", () -> testLimit(2));
        run("Top 3", () -> testLimit(3));
        run("Top 5", () -> testLimit(5));
        run("K equals matching count", () -> testLimit(6));
        run("K exceeds matching count", () -> testLimit(20));
        run("Integer.MAX_VALUE without proportional allocation", () -> testLimit(Integer.MAX_VALUE));
        run("Invalid K is rejected descriptively", TopKSearchTests::testInvalidK);
        run("Equal-score filename ties at cutoff", TopKSearchTests::testTies);
        run("Normalization and repeated query terms", TopKSearchTests::testNormalization);
        run("Exact score bits and query addition order", TopKSearchTests::testScoreOrder);
        run("Empty and punctuation-only queries", TopKSearchTests::testEmptyQueries);
        run("Unknown terms and mixed known/unknown queries", TopKSearchTests::testUnknown);
        run("All twelve stop words", TopKSearchTests::testStopWords);
        run("Empty index before and after build", TopKSearchTests::testEmptyIndex);
        run("Independent lists and repeated calls", TopKSearchTests::testIndependentCalls);
        run("Original six-document corpus", TopKSearchTests::testDefaultCorpus);
        run("Separate nine-document fictional corpus", TopKSearchTests::testDemoCorpus);
        run("Seeded generated TF collections and prefixes", TopKSearchTests::testGenerated);
        run("Sparse candidate metadata access, no document iteration", TopKSearchTests::testSparseCandidates);
        run("No document-text reads during retrieval", TopKSearchTests::testNoFileScan);
        run("No delegation to full search", TopKSearchTests::testNoDelegation);
        run("Heap bound, invariants and extraction under arbitrary arrival order", TopKSearchTests::testHeap);
        run("Successful rebuild replaces scores and IDs", TopKSearchTests::testRebuild);
        run("Failed rebuild preserves the usable index", TopKSearchTests::testFailedRebuild);
        run("Keyword, phrase, Boolean and statistics unchanged", TopKSearchTests::testCompatibility);
        System.out.println("Total passed: " + passed);
        System.out.println("Total failed: " + failed);
        if (failed != 0) {
            System.exit(1);
        }
    }

    @FunctionalInterface
    private interface TestCase {
        void run() throws Exception;
    }

    private static void run(String name, TestCase test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS: " + name);
        } catch (Exception | AssertionError failure) {
            failed++;
            System.out.println("FAIL: " + name);
            failure.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void sameResults(List<SearchResult> expected, List<SearchResult> actual) {
        check(expected.size() == actual.size(), "Result count: " + expected.size() + " vs " + actual.size());
        for (int i = 0; i < expected.size(); i++) {
            SearchResult left = expected.get(i);
            SearchResult right = actual.get(i);
            check(left.getDocument().getId() == right.getDocument().getId(), "Document ID at " + i);
            check(left.getDocument().getFilename().equals(right.getDocument().getFilename()), "Filename at " + i);
            check(Double.doubleToLongBits(left.getScore()) == Double.doubleToLongBits(right.getScore()),
                    "Exact score bits at " + i + ": " + left.getScore() + " vs " + right.getScore());
        }
    }

    private static void prefix(SearchEngine engine, String query, int k) {
        // Calling search() is intentional in this test oracle, never in Top-K production code.
        List<SearchResult> all = engine.search(query);
        List<SearchResult> expected = new ArrayList<>();
        for (int i = 0; i < Math.min(k, all.size()); i++) {
            expected.add(all.get(i));
        }
        sameResults(expected, engine.searchTopK(query, k));
    }

    private static Fixture rankedFixture() throws IOException {
        return new Fixture("java", "java java java java", "java java", "networking",
                "java java java java java java", "java java java", "java java java java java");
    }

    private static void testLimit(int k) throws IOException {
        try (Fixture fixture = rankedFixture()) {
            check(fixture.engine.search("java").size() == 6, "Six positive matches");
            prefix(fixture.engine, "java", k);
            check(fixture.engine.searchTopK("java", k).get(0).getDocument().getFilename()
                    .equals("doc00004.txt"), "Highest TF must win over filename order");
        }
    }

    private static void testInvalidK() throws IOException {
        try (Fixture fixture = rankedFixture()) {
            for (int k : new int[]{0, -1, Integer.MIN_VALUE}) {
                try {
                    fixture.engine.searchTopK("java", k);
                    throw new AssertionError("Expected invalid K rejection: " + k);
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().contains("at least 1"), "Descriptive K error");
                }
            }
            prefix(fixture.engine, "java", 2);
        }
    }

    private static void testTies() throws IOException {
        try (Fixture fixture = new Fixture("java", "java", "java", "java java", "java", "java")) {
            List<SearchResult> top = fixture.engine.searchTopK("java", 3);
            check(resultNames(top).equals(List.of("doc00003.txt", "doc00000.txt", "doc00001.txt")),
                    "Earlier filenames win equal-score boundary ties");
            for (int k = 1; k <= 7; k++) {
                prefix(fixture.engine, "java", k);
            }
        }
    }

    private static void testNormalization() throws IOException {
        try (Fixture fixture = new Fixture("Java search 25", "java java", "search search", "25 search")) {
            for (String query : List.of("java search 25", "JAVA,search_25!!", "java java SEARCH search 25 25",
                    "the JAVA and search OR 25", "\"java search\"")) {
                for (int k : new int[]{1, 2, 4, 9}) {
                    prefix(fixture.engine, query, k);
                }
            }
            sameResults(fixture.engine.searchTopK("java search 25", 4),
                    fixture.engine.searchTopK("JAVA,java SEARCH search 25 25", 4));
        }
    }

    private static void testScoreOrder() throws IOException {
        // Unequal df and TF expose rounding differences if contributions are reordered.
        try (Fixture fixture = generatedFixture(97, 815)) {
            String[] orders = {"java search networking algorithms rare", "rare algorithms networking search java",
                    "search rare java algorithms networking", "java unknown java rare search search"};
            for (String query : orders) {
                for (int k : new int[]{1, 5, 25, Integer.MAX_VALUE}) {
                    prefix(fixture.engine, query, k);
                }
            }
        }
    }

    private static void testEmptyQueries() throws IOException {
        try (Fixture fixture = rankedFixture()) {
            for (String query : List.of("", " \t\r\n", "!?,---___")) {
                check(fixture.engine.searchTopK(query, 5).isEmpty(), "Empty normalized query");
            }
        }
    }

    private static void testUnknown() throws IOException {
        try (Fixture fixture = rankedFixture()) {
            check(fixture.engine.searchTopK("unknown zzzabsent", Integer.MAX_VALUE).isEmpty(), "Unknown query");
            prefix(fixture.engine, "unknown java zzzabsent", 3);
        }
    }

    private static void testStopWords() throws IOException {
        try (Fixture fixture = new Fixture("and or the", "java and search")) {
            check(fixture.engine.searchTopK("a an the and or is are of to in for with", 1).isEmpty(),
                    "Stop words must not become ranked candidates");
            prefix(fixture.engine, "java and the search", 2);
        }
    }

    private static void testEmptyIndex() throws IOException {
        check(new SearchEngine().searchTopK("java", Integer.MAX_VALUE).isEmpty(), "Unbuilt index");
        try (Fixture fixture = new Fixture()) {
            check(fixture.engine.searchTopK("java", 1).isEmpty(), "Built empty index");
        }
    }

    private static void testIndependentCalls() throws IOException {
        try (Fixture fixture = rankedFixture()) {
            List<SearchResult> first = fixture.engine.searchTopK("java", 3);
            List<SearchResult> snapshot = List.copyOf(first);
            first.clear();
            List<SearchResult> second = fixture.engine.searchTopK("java", 3);
            sameResults(snapshot, second);
            prefix(fixture.engine, "networking", 1);
            prefix(fixture.engine, "java", 1);
            second.remove(0);
            prefix(fixture.engine, "java", 5);
        }
    }

    private static void testDefaultCorpus() throws IOException {
        SearchEngine engine = new SearchEngine();
        engine.buildIndex("data");
        check(engine.getDocumentCount() == 6 && engine.getUniqueTermCount() == 75
                && engine.getTotalTokens() == 120, "Default statistics remain 6/75/120");
        check(resultNames(engine.searchTopK("java", 2)).equals(
                List.of("java_programming.txt", "academic_notice.txt")), "Default ranked filenames");
        for (String query : List.of("java", "software engineering", "java networking algorithms", "AND java java")) {
            for (int k : new int[]{1, 2, 3, 5, 6, Integer.MAX_VALUE}) {
                prefix(engine, query, k);
            }
        }
    }

    private static void testDemoCorpus() throws IOException {
        SearchEngine engine = new SearchEngine();
        engine.buildIndex("data/phrase_samples");
        check(engine.getDocumentCount() == 9, "Demo must remain separate");
        for (String query : List.of("java search", "programming laboratory", "error error", "project presentation")) {
            for (int k : new int[]{1, 2, 3, 5, 9, Integer.MAX_VALUE}) {
                prefix(engine, query, k);
            }
        }
    }

    private static void testGenerated() throws IOException {
        Random queries = new Random(1705);
        String[] terms = {"java", "search", "networking", "algorithms", "rare", "unknown", "and"};
        for (int size : new int[]{1, 2, 7, 31, 128, 257}) {
            try (Fixture fixture = generatedFixture(size, size + 1705)) {
                for (int q = 0; q < 60; q++) {
                    StringBuilder query = new StringBuilder();
                    for (int t = 0; t < 1 + q % 9; t++) {
                        query.append(terms[queries.nextInt(terms.length)]).append(' ');
                    }
                    for (int k : new int[]{1, 2, 3, 5, 10, size, size + 1}) {
                        prefix(fixture.engine, query.toString(), k);
                    }
                }
            }
        }
    }

    private static void testSparseCandidates() throws Exception {
        String[] texts = new String[300];
        Arrays.fill(texts, "unrelated");
        texts[5] = "java";
        texts[297] = "java java";
        try (Fixture fixture = new Fixture(texts)) {
            List<SearchResult> expected = fixture.engine.search("java");
            Field field = SearchEngine.class.getDeclaredField("documents");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Document> original = (List<Document>) field.get(fixture.engine);
            int[] lookups = {0};
            // This test-only metadata wrapper rejects an all-document iteration.
            field.set(fixture.engine, new AbstractList<Document>() {
                public Document get(int i) { lookups[0]++; return original.get(i); }
                public int size() { return original.size(); }
                public Iterator<Document> iterator() { throw new AssertionError("Full document scan"); }
            });
            sameResults(expected, fixture.engine.searchTopK("java", 2));
            check(lookups[0] == 2, "Only two posting-driven candidates need metadata");
        }
    }

    private static void testNoFileScan() throws IOException {
        try (Fixture fixture = rankedFixture()) {
            List<SearchResult> expected = fixture.engine.searchTopK("java", 3);
            for (Path file : fixture.files) {
                Files.delete(file);
            }
            sameResults(expected, fixture.engine.searchTopK("java", 3));
        }
    }

    private static void testNoDelegation() throws IOException {
        try (Fixture fixture = new Fixture("java", "java java", "networking")) {
            SearchEngine engine = new SearchEngine() {
                @Override
                public List<SearchResult> search(String query) {
                    throw new AssertionError("Top-K must not call full search()");
                }
            };
            engine.buildIndex(fixture.directory.toString());
            sameResults(fixture.engine.searchTopK("java", 1), engine.searchTopK("java", 1));
        }
    }

    private static void testHeap() throws Exception {
        // Reflection is confined to tests: verify the actual private heap after
        // EVERY offer/extraction without adding instrumentation to the public API.
        Class<?> type = Class.forName("SearchEngine$BoundedResultHeap");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Method offer = type.getDeclaredMethod("offer", SearchResult.class);
        Method remove = type.getDeclaredMethod("removeWorst");
        Field field = type.getDeclaredField("entries");
        offer.setAccessible(true);
        remove.setAccessible(true);
        field.setAccessible(true);
        List<SearchResult> arrivals = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            arrivals.add(new SearchResult(new Document(i + 1, filename(i)), (i * 37) % 13 + 1.0));
        }
        Comparator<SearchResult> ranking = Comparator.comparingDouble(SearchResult::getScore).reversed()
                .thenComparing(result -> result.getDocument().getFilename());
        Collections.shuffle(arrivals, new Random(1705));
        List<SearchResult> ranked = new ArrayList<>(arrivals);
        ranked.sort(ranking); // Full sorting is permitted only in the test oracle.
        for (int k : new int[]{1, 2, 3, 5, 10, 249, 250, Integer.MAX_VALUE}) {
            Object heap = constructor.newInstance(k);
            @SuppressWarnings("unchecked")
            List<SearchResult> entries = (List<SearchResult>) field.get(heap);
            for (SearchResult candidate : arrivals) {
                offer.invoke(heap, candidate);
                check(entries.size() <= k, "Heap exceeded K");
                for (int child = 1; child < entries.size(); child++) {
                    check(ranking.compare(entries.get((child - 1) / 2), entries.get(child)) >= 0,
                            "Parent must be at least as bad as child");
                }
            }
            check(entries.size() == Math.min(k, arrivals.size()), "Retained count");
            List<SearchResult> extracted = new ArrayList<>();
            while (!entries.isEmpty()) {
                extracted.add((SearchResult) remove.invoke(heap));
            }
            Collections.reverse(extracted);
            sameResults(ranked.subList(0, Math.min(k, ranked.size())), extracted);
        }
    }

    private static void testRebuild() throws IOException {
        try (Fixture fixture = new Fixture("java", "java java", "networking")) {
            List<SearchResult> before = fixture.engine.searchTopK("java", 2);
            fixture.engine.buildIndex(fixture.directory.toString());
            sameResults(before, fixture.engine.searchTopK("java", 2));
            Files.writeString(fixture.files.get(0), "java java java java");
            Files.delete(fixture.files.get(1));
            fixture.engine.buildIndex(fixture.directory.toString());
            check(resultNames(fixture.engine.searchTopK("java", 9)).equals(List.of(filename(0))),
                    "Deleted document must disappear");
            prefix(fixture.engine, "java networking", 2);
            check(fixture.engine.searchTopK("java", 1).get(0).getScore() > before.get(0).getScore(),
                    "Updated TF must change score");
            try (Fixture replacement = new Fixture("search", "search search")) {
                fixture.engine.buildIndex(replacement.directory.toString());
                check(fixture.engine.searchTopK("java", 5).isEmpty(), "No old postings after replacement");
                prefix(fixture.engine, "search", 2);
            }
        }
    }

    private static void testFailedRebuild() throws IOException {
        try (Fixture fixture = rankedFixture()) {
            List<SearchResult> before = fixture.engine.searchTopK("java", 5);
            int tokens = fixture.engine.getTotalTokens();
            Files.writeString(fixture.files.get(0), "replacement text");
            Files.write(fixture.files.get(6), new byte[]{(byte) 0xC3, (byte) 0x28});
            for (Path folder : List.of(fixture.directory.resolve("missing"), fixture.directory)) {
                try {
                    fixture.engine.buildIndex(folder.toString());
                    throw new AssertionError("Expected failed rebuild");
                } catch (IOException expected) {
                    sameResults(before, fixture.engine.searchTopK("java", 5));
                    prefix(fixture.engine, "java", 5);
                    check(fixture.engine.getTotalTokens() == tokens, "Failed build changed statistics");
                }
            }
        }
    }

    private static void testCompatibility() throws IOException {
        try (Fixture fixture = new Fixture("Java and Java Search", "java programming", "networking laboratory")) {
            SearchEngine engine = fixture.engine;
            List<SearchResult> keyword = engine.search("java search");
            List<String> phrase = documentNames(engine.searchPhrase("java and java"));
            List<String> bool = documentNames(engine.searchBoolean("java OR networking AND laboratory"));
            check(phrase.equals(List.of(filename(0))), "Phrase fixture");
            check(bool.equals(List.of(filename(0), filename(1), filename(2))), "Boolean fixture");
            int terms = engine.getUniqueTermCount();
            int tokens = engine.getTotalTokens();
            var positions = engine.getPositionalPostings("java");
            for (int k : new int[]{1, 2, 3, Integer.MAX_VALUE}) {
                prefix(engine, "java search", k);
            }
            sameResults(keyword, engine.search("java search"));
            check(phrase.equals(documentNames(engine.searchPhrase("java and java"))), "Phrase changed");
            check(bool.equals(documentNames(engine.searchBoolean("java OR networking AND laboratory"))), "Boolean changed");
            check(engine.searchBoolean("java AND networking").isEmpty(), "Boolean intersection changed");
            check(engine.getDocumentCount() == 3 && engine.getUniqueTermCount() == terms
                    && engine.getTotalTokens() == tokens, "Statistics changed");
            check(positions.equals(engine.getPositionalPostings("java")), "Positions changed");
        }
    }

    private static List<String> resultNames(List<SearchResult> results) {
        List<String> names = new ArrayList<>();
        for (SearchResult result : results) { names.add(result.getDocument().getFilename()); }
        return names;
    }

    private static List<String> documentNames(List<Document> documents) {
        List<String> names = new ArrayList<>();
        for (Document document : documents) { names.add(document.getFilename()); }
        return names;
    }

    private static String filename(int i) {
        return String.format(Locale.ROOT, "doc%05d.txt", i);
    }

    private static Fixture generatedFixture(int size, long seed) throws IOException {
        Random random = new Random(seed);
        String[] texts = new String[size];
        String[] terms = {"java", "search", "networking", "algorithms", "rare"};
        for (int i = 0; i < size; i++) {
            StringBuilder text = new StringBuilder("fictional laboratory and ");
            for (int t = 0; t < terms.length; t++) {
                int frequency = t == 0 ? 1 + random.nextInt(12) : random.nextInt(Math.max(2, 9 - t * 2));
                text.append((terms[t] + " ").repeat(frequency));
            }
            texts[i] = text.toString();
        }
        return new Fixture(texts);
    }

    private static void benchmark() throws IOException {
        System.out.println("Optional synthetic benchmark; index/file creation excluded from query timings.");
        System.out.println("20 warmup calls per method/query; 9 measured calls per method, rotated order.");
        System.out.println("Median query nanoseconds; no time assertions. JIT, GC, allocation and machine load affect results.");
        System.out.println("N,M,query,method,K,median_ns");
        int[] limits = {0, 1, 5, 10, 100}; // zero denotes the original full search.
        for (int size : new int[]{500, 2000, 5000}) {
            try (Fixture fixture = generatedFixture(size, 1705 + size)) {
                for (String query : List.of("java", "java search networking")) {
                    int matches = fixture.engine.search(query).size();
                    for (int round = 0; round < 20; round++) {
                        for (int k : limits) { consume(benchmarkQuery(fixture.engine, query, k)); }
                    }
                    long[][] times = new long[limits.length][9];
                    for (int round = 0; round < 9; round++) {
                        for (int step = 0; step < limits.length; step++) {
                            int method = (step + round) % limits.length;
                            long start = System.nanoTime();
                            List<SearchResult> result = benchmarkQuery(fixture.engine, query, limits[method]);
                            times[method][round] = System.nanoTime() - start;
                            consume(result); // Outside the measured interval; keeps output observable.
                        }
                    }
                    for (int method = 0; method < limits.length; method++) {
                        Arrays.sort(times[method]);
                        System.out.printf(Locale.ROOT, "%d,%d,%s,%s,%s,%d%n", size, matches, query,
                                method == 0 ? "full" : "topK", method == 0 ? "all" : limits[method], times[method][4]);
                    }
                }
            }
        }
        System.out.println("Benchmark sink: " + benchmarkSink);
    }

    private static List<SearchResult> benchmarkQuery(SearchEngine engine, String query, int k) {
        return k == 0 ? engine.search(query) : engine.searchTopK(query, k);
    }

    private static void consume(List<SearchResult> results) {
        benchmarkSink = results.size() + (results.isEmpty() ? 0 : results.get(0).getScore());
    }

    private static class Fixture implements AutoCloseable {
        private final Path directory = Files.createTempDirectory("iit-top-k-");
        private final List<Path> files = new ArrayList<>();
        private final SearchEngine engine = new SearchEngine();

        private Fixture(String... texts) throws IOException {
            try {
                // Creation order is deliberately opposite to the loader's filename order.
                for (int i = texts.length - 1; i >= 0; i--) {
                    Path file = directory.resolve(filename(i));
                    files.add(file);
                    Files.writeString(file, texts[i], StandardCharsets.UTF_8);
                }
                engine.buildIndex(directory.toString());
            } catch (IOException failure) {
                try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            // Tests address files by their filename/document order.
            Collections.reverse(files);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            // Delete only explicitly recorded files in this owned temp directory.
            for (Path file : files) {
                try { Files.deleteIfExists(file); } catch (IOException cleanup) {
                    if (failure == null) { failure = cleanup; } else { failure.addSuppressed(cleanup); }
                }
            }
            try { Files.delete(directory); } catch (IOException cleanup) {
                if (failure == null) { failure = cleanup; } else { failure.addSuppressed(cleanup); }
            }
            if (failure != null) { throw failure; }
        }
    }
}
