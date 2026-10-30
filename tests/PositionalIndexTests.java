import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class PositionalIndexTests {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        runTest("Adjacent tokens", PositionalIndexTests::testAdjacent);
        runTest("Reverse-order tokens", PositionalIndexTests::testReverseOrder);
        runTest("Tokens separated by other words", PositionalIndexTests::testGaps);
        runTest("Repeated and nonconsecutive words", PositionalIndexTests::testRepeatedWords);
        runTest("Case, punctuation, and ASCII tokens", PositionalIndexTests::testNormalization);
        runTest("Original positions include all stop words", PositionalIndexTests::testStopWordPositions);
        runTest("Stop-word postings preserve searchable frequencies", PositionalIndexTests::testFrequencies);
        runTest("Unknown terms and missing documents", PositionalIndexTests::testMissingEntries);
        runTest("Deeply read-only positional snapshots", PositionalIndexTests::testReadOnly);
        runTest("Legacy addDocument compatibility", PositionalIndexTests::testLegacyApi);
        runTest("Rebuild replaces positional entries", PositionalIndexTests::testRebuild);
        runTest("Failed rebuild preserves both indexes", PositionalIndexTests::testFailedRebuild);
        runTest("Exact keyword scores, ordering, and statistics", PositionalIndexTests::testKeywordCompatibility);
        System.out.println("Total passed: " + passed);
        System.out.println("Total failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    @FunctionalInterface
    private interface TestCase {
        void run() throws Exception;
    }

    private static void runTest(String name, TestCase test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS: " + name);
        } catch (Exception | AssertionError failure) {
            failed++;
            System.out.println("FAIL: " + name + " - " + failure);
            failure.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void addText(InvertedIndex index, int documentId, String text) {
        index.addDocumentWithPositions(new Document(documentId, "doc" + documentId + ".txt"),
                new TextProcessor().tokenize(text));
    }

    private static void checkPositions(InvertedIndex index, String term, int documentId,
                                       Integer... expected) {
        List<Integer> actual = index.getPositionalPostings(term).getOrDefault(documentId, List.of());
        check(actual.equals(List.of(expected)), term + " in doc " + documentId
                + ": expected " + List.of(expected) + ", actual " + actual);
    }

    private static void testAdjacent() {
        InvertedIndex index = new InvertedIndex();
        addText(index, 1, "java search");
        checkPositions(index, "java", 1, 0);
        checkPositions(index, "search", 1, 1);
    }

    private static void testReverseOrder() {
        InvertedIndex index = new InvertedIndex();
        addText(index, 1, "java search");
        addText(index, 2, "search java");
        check(index.getPositionalPostings("java").equals(Map.of(1, List.of(0), 2, List.of(1))),
                "Positions must distinguish token order across documents");
        checkPositions(index, "search", 2, 0);
    }

    private static void testGaps() {
        InvertedIndex index = new InvertedIndex();
        addText(index, 1, "java useful fast search");
        checkPositions(index, "java", 1, 0);
        checkPositions(index, "useful", 1, 1);
        checkPositions(index, "fast", 1, 2);
        checkPositions(index, "search", 1, 3);
    }

    private static void testRepeatedWords() {
        InvertedIndex index = new InvertedIndex();
        addText(index, 1, "java java search java guide java");
        checkPositions(index, "java", 1, 0, 1, 3, 5);
        checkPositions(index, "search", 1, 2);
        check(index.getPostings("java").equals(Map.of(1, 4)), "Repeated words retain raw TF");
    }

    private static void testNormalization() {
        String text = "  JaVa,,SEARCH-engine.TEST! 25_2026 caf\u00e9 \u09ac\u09be\u0982\u09b2\u09be  ";
        List<String> expected = List.of("java", "search", "engine", "test", "25", "2026", "caf");
        TextProcessor processor = new TextProcessor();
        check(processor.tokenize(text).equals(expected), "Use the existing ASCII tokenization");
        check(processor.process(text).equals(expected), "Legacy processing stays unchanged");
        InvertedIndex index = new InvertedIndex();
        addText(index, 1, text);
        for (int position = 0; position < expected.size(); position++) {
            checkPositions(index, expected.get(position), 1, position);
        }
    }

    private static void testStopWordPositions() {
        InvertedIndex index = new InvertedIndex();
        addText(index, 1, "Java and Java Search");
        checkPositions(index, "java", 1, 0, 2);
        checkPositions(index, "and", 1, 1);
        checkPositions(index, "search", 1, 3);
        String stopWords = "a an the and or is are of to in for with";
        addText(index, 2, stopWords + " JAVA");
        List<String> words = new TextProcessor().tokenize(stopWords);
        for (int position = 0; position < words.size(); position++) {
            checkPositions(index, words.get(position), 2, position);
        }
        checkPositions(index, "java", 2, 12);
        check(new TextProcessor().process(stopWords + " JAVA").equals(List.of("java")),
                "process still removes exactly the configured stop words");
    }

    private static void testFrequencies() {
        InvertedIndex index = new InvertedIndex();
        addText(index, 1, "Java and Java Search");
        addText(index, 2, "the java");
        addText(index, 3, "a an the and or is are of to in for with");
        addText(index, 4, " ... ");
        check(index.getPostings("java").equals(Map.of(1, 2, 2, 1)), "Legacy java frequencies");
        check(index.getPostings("java").size() == 2, "Document frequency remains two");
        check(index.getPostings("search").equals(Map.of(1, 1)), "Legacy search frequency");
        for (String stopWord : new TextProcessor().tokenize("a an the and or is are of to in for with")) {
            check(index.getPostings(stopWord).isEmpty(), "Stop words must not become searchable");
            check(!index.getPositionalPostings(stopWord).isEmpty(), "Stop words retain positions");
        }
        check(index.getUniqueTermCount() == 2, "Statistics exclude stop-word terms");
        check(index.getTotalTokens() == 4, "Statistics exclude stop-word occurrences");
    }

    private static void testMissingEntries() {
        InvertedIndex index = new InvertedIndex();
        check(index.getPositionalPostings("java").isEmpty(), "Empty index returns empty postings");
        addText(index, 1, "java");
        addText(index, 2, "search");
        check(index.getPositionalPostings("unknown").isEmpty(), "Unknown term returns an empty map");
        checkPositions(index, "java", 2);
        checkPositions(index, "java", 999);
        check(!index.getPositionalPostings("java").containsKey(999), "Missing ID has no entry");
        check(index.getUniqueTermCount() == 2 && index.getTotalTokens() == 2,
                "Missing lookups must not change the index");
    }

    private static void expectReadOnly(Runnable mutation) {
        boolean rejected = false;
        try {
            mutation.run();
        } catch (UnsupportedOperationException expected) {
            rejected = true;
        }
        check(rejected, "Positional API must reject mutation");
    }

    private static void testReadOnly() {
        InvertedIndex index = new InvertedIndex();
        addText(index, 1, "java and java");
        Map<Integer, List<Integer>> snapshot = index.getPositionalPostings("java");
        expectReadOnly(() -> snapshot.put(2, List.of(9)));
        expectReadOnly(() -> snapshot.get(1).add(9));
        expectReadOnly(() -> snapshot.get(1).set(0, 9));
        expectReadOnly(() -> snapshot.entrySet().iterator().next().setValue(List.of(9)));
        expectReadOnly(() -> snapshot.entrySet().iterator().remove());
        expectReadOnly(() -> index.getPositionalPostings("missing").put(1, List.of(0)));
        addText(index, 1, "java");
        check(snapshot.equals(Map.of(1, List.of(0, 2))), "Snapshot must not change after indexing");
        checkPositions(index, "java", 1, 0, 2, 3);
    }

    private static void testLegacyApi() {
        InvertedIndex index = new InvertedIndex();
        Document document = new Document(1, "legacy.txt");
        List<String> tokens = List.of("java", "java", "search");
        index.addDocument(document, tokens);
        index.addDocument(document, List.of("and", "JAVA", "java"));
        check(index.getPostings("java").equals(Map.of(1, 3)), "Legacy calls accumulate TF");
        check(index.getPostings("and").equals(Map.of(1, 1)), "Legacy API counts supplied tokens verbatim");
        check(index.getPostings("JAVA").equals(Map.of(1, 1)), "Legacy API does not normalize supplied tokens");
        check(index.getUniqueTermCount() == 4 && index.getTotalTokens() == 6, "Legacy counts unchanged");
        checkPositions(index, "java", 1, 0, 1, 5);
        checkPositions(index, "and", 1, 3);
        check(tokens.equals(List.of("java", "java", "search")), "Input lists remain unchanged");
    }

    private static void testRebuild() throws IOException {
        try (TestCollection collection = new TestCollection("Java and Java Search", "search java", "the")) {
            SearchEngine engine = new SearchEngine();
            engine.buildIndex(collection.directory.toString());
            Map<Integer, List<Integer>> before = engine.getPositionalPostings("java");
            check(before.equals(Map.of(1, List.of(0, 2), 2, List.of(1))), "Original full positions");
            engine.buildIndex(collection.directory.toString());
            check(engine.getPositionalPostings("java").equals(before), "Rebuild cannot duplicate positions");
            check(engine.getPositionalPostings("and").equals(Map.of(1, List.of(1))), "Stop-word rebuild");
            checkStats(engine, 3, 2, 5);
            Files.writeString(collection.files.get(0), "network and java", StandardCharsets.UTF_8);
            engine.buildIndex(collection.directory.toString());
            check(engine.getPositionalPostings("java").equals(Map.of(1, List.of(2), 2, List.of(1))),
                    "Changed document replaces old positions");
            check(engine.getPositionalPostings("search").equals(Map.of(2, List.of(0))),
                    "Removed occurrences must disappear");
            checkStats(engine, 3, 3, 4);
            engine.buildIndex(collection.directory.toString());
            check(engine.getPositionalPostings("java").equals(Map.of(1, List.of(2), 2, List.of(1))),
                    "Repeated rebuild still has no duplicates");
        }
    }

    private static void testFailedRebuild() throws IOException {
        try (TestCollection collection = new TestCollection("java and search", "search", "java java java")) {
            SearchEngine engine = new SearchEngine();
            engine.buildIndex(collection.directory.toString());
            Map<Integer, List<Integer>> before = engine.getPositionalPostings("java");
            Files.writeString(collection.files.get(0), "network", StandardCharsets.UTF_8);
            Files.write(collection.files.get(1), new byte[] {(byte) 0xc3, (byte) 0x28});
            boolean failedRead = false;
            try {
                engine.buildIndex(collection.directory.toString());
            } catch (IOException expected) {
                failedRead = true;
            }
            check(failedRead, "Invalid UTF-8 must fail after reading the first changed document");
            check(engine.getPositionalPostings("java").equals(before), "Keep previous positions");
            check(engine.getPositionalPostings("and").equals(Map.of(1, List.of(1))), "Keep stop-word positions");
            check(engine.getPositionalPostings("network").isEmpty(), "No partial positional publication");
            checkStats(engine, 3, 2, 6);
            double idf = Math.log(4.0 / 3.0) + 1.0;
            checkResults(engine.search("java"), List.of("z.txt", "a.txt"), 3.0 * idf, idf);
        }
    }

    private static void testKeywordCompatibility() throws IOException {
        try (TestCollection collection = new TestCollection("java and search", "search", "java java java")) {
            SearchEngine engine = new SearchEngine();
            engine.buildIndex(collection.directory.toString());
            checkStats(engine, 3, 2, 6);
            double idf = Math.log(4.0 / 3.0) + 1.0;
            checkResults(engine.search("java"), List.of("z.txt", "a.txt"), 3.0 * idf, idf);
            checkResults(engine.search("search"), List.of("a.txt", "b.txt"), idf, idf);
            checkResults(engine.search("java search"), List.of("z.txt", "a.txt", "b.txt"),
                    3.0 * idf, idf + idf, idf);
            for (String query : List.of("JAVA,java and search", "\"java search\"", "java search java")) {
                checkResults(engine.search(query), List.of("z.txt", "a.txt", "b.txt"),
                        3.0 * idf, idf + idf, idf);
            }
            check(engine.search("the AND or in").isEmpty(), "Stop words stay unsearchable");
            check(engine.search("unknown").isEmpty(), "Unknown terms stay unmatched");
            check(engine.search("").isEmpty(), "Empty query stays unmatched");
        }
    }

    private static void checkStats(SearchEngine engine, int documents, int terms, int tokens) {
        check(engine.getDocumentCount() == documents, "Document count");
        check(engine.getUniqueTermCount() == terms, "Searchable unique term count");
        check(engine.getTotalTokens() == tokens, "Searchable token count");
    }

    private static void checkResults(List<SearchResult> actual, List<String> filenames, double... scores) {
        check(actual.size() == filenames.size(), "Result count");
        for (int i = 0; i < actual.size(); i++) {
            check(actual.get(i).getDocument().getFilename().equals(filenames.get(i)), "Filename/order at " + i);
            check(Double.doubleToLongBits(actual.get(i).getScore()) == Double.doubleToLongBits(scores[i]),
                    "Exact score at " + i + ": expected " + scores[i] + ", actual " + actual.get(i).getScore());
        }
    }

    private static class TestCollection implements AutoCloseable {
        private final Path directory;
        private final List<Path> files;

        private TestCollection(String first, String second, String third) throws IOException {
            directory = Files.createTempDirectory("iit-positional-tests-");
            files = List.of(directory.resolve("a.txt"), directory.resolve("b.txt"), directory.resolve("z.txt"));
            try {
                Files.writeString(files.get(0), first, StandardCharsets.UTF_8);
                Files.writeString(files.get(1), second, StandardCharsets.UTF_8);
                Files.writeString(files.get(2), third, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                try {
                    close();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            // Only remove these named temporary files and their owned directory.
            IOException failure = null;
            for (Path file : files) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            try {
                Files.delete(directory);
            } catch (IOException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
