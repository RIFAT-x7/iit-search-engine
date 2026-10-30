import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PhraseSearchTests {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        runTest("Two-word phrase", PhraseSearchTests::testTwoWords);
        runTest("Reverse-order rejection", PhraseSearchTests::testReverseOrder);
        runTest("Nonadjacent-word rejection", PhraseSearchTests::testGaps);
        runTest("Three-word phrases", PhraseSearchTests::testThreeWords);
        runTest("Repeated terms and overlapping occurrences", PhraseSearchTests::testRepeatedTerms);
        runTest("Stop words inside phrases", PhraseSearchTests::testStopWords);
        runTest("Stop-word-only phrases", PhraseSearchTests::testStopWordOnly);
        runTest("Case and punctuation normalization", PhraseSearchTests::testNormalization);
        runTest("One-word phrases", PhraseSearchTests::testOneWord);
        runTest("Empty, unknown, and nonmatching input", PhraseSearchTests::testNoMatches);
        runTest("Unique documents in filename order", PhraseSearchTests::testResultOrder);
        runTest("No cross-document matches", PhraseSearchTests::testDocumentBoundaries);
        runTest("All offsets share one phrase start", PhraseSearchTests::testConsistentStart);
        runTest("Queries use the built index without reading files", PhraseSearchTests::testNoFileScan);
        runTest("Successful and failed rebuilds", PhraseSearchTests::testRebuild);
        runTest("Fictional IIT phrase collection", PhraseSearchTests::testSampleCollection);
        runTest("Keyword scores and semantics unchanged", PhraseSearchTests::testKeywordCompatibility);
        runTest("Default corpus statistics and ranking unchanged", PhraseSearchTests::testDefaultCorpus);
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

    private static void checkPhrase(SearchEngine engine, String phrase, String... expected) {
        List<String> actual = new ArrayList<>();
        for (Document document : engine.searchPhrase(phrase)) {
            actual.add(document.getFilename());
        }
        check(actual.equals(List.of(expected)), "Phrase [" + phrase + "]: expected "
                + List.of(expected) + ", actual " + actual);
    }

    private static void testTwoWords() throws IOException {
        try (TestCollection collection = new TestCollection("Java and Java Search", "network", "java")) {
            checkPhrase(collection.engine, "java search", "a.txt");
            checkPhrase(collection.engine, "and java", "a.txt");
            checkPhrase(collection.engine, "java java");
        }
    }

    private static void testReverseOrder() throws IOException {
        try (TestCollection collection = new TestCollection("java search", "search java", "network")) {
            checkPhrase(collection.engine, "java search", "a.txt");
            checkPhrase(collection.engine, "search java", "b.txt");
        }
    }

    private static void testGaps() throws IOException {
        try (TestCollection collection = new TestCollection("java useful search", "java and search", "java search")) {
            checkPhrase(collection.engine, "java search", "c.txt");
            checkPhrase(collection.engine, "java useful search", "a.txt");
        }
    }

    private static void testThreeWords() throws IOException {
        try (TestCollection collection = new TestCollection("Java and Java Search", "java search engine", "engine search java")) {
            checkPhrase(collection.engine, "java and java", "a.txt");
            checkPhrase(collection.engine, "java search engine", "b.txt");
            checkPhrase(collection.engine, "engine search java", "c.txt");
            checkPhrase(collection.engine, "java engine search");
        }
    }

    private static void testRepeatedTerms() throws IOException {
        try (TestCollection collection = new TestCollection("java java java", "java and java search", "java search java search")) {
            checkPhrase(collection.engine, "java java", "a.txt");
            checkPhrase(collection.engine, "java java java", "a.txt");
            checkPhrase(collection.engine, "java java java java");
            checkPhrase(collection.engine, "java search", "b.txt", "c.txt");
            checkPhrase(collection.engine, "java search java search", "c.txt");
        }
    }

    private static void testStopWords() throws IOException {
        try (TestCollection collection = new TestCollection("java and java search", "java or java search", "the java search")) {
            checkPhrase(collection.engine, "java and java", "a.txt");
            checkPhrase(collection.engine, "java or java", "b.txt");
            checkPhrase(collection.engine, "and java", "a.txt");
            checkPhrase(collection.engine, "java search", "a.txt", "b.txt", "c.txt");
        }
    }

    private static void testStopWordOnly() throws IOException {
        try (TestCollection collection = new TestCollection("in the room", "the in room", "the and the")) {
            checkPhrase(collection.engine, "in the", "a.txt");
            checkPhrase(collection.engine, "the in", "b.txt");
            checkPhrase(collection.engine, "the", "a.txt", "b.txt", "c.txt");
            checkPhrase(collection.engine, "the and the", "c.txt");
            check(collection.engine.search("in the").isEmpty(), "Keywords still filter stop words");
        }
    }

    private static void testNormalization() throws IOException {
        try (TestCollection collection = new TestCollection("JAVA,\n Search-engine 25", "java useful search", "network")) {
            checkPhrase(collection.engine, "  JaVa--SEARCH! ", "a.txt");
            checkPhrase(collection.engine, "search_engine", "a.txt");
            checkPhrase(collection.engine, "engine 25", "a.txt");
            // Quotes are ordinary separators here, not parsed query syntax.
            checkPhrase(collection.engine, "\"Java Search\"", "a.txt");
        }
    }

    private static void testOneWord() throws IOException {
        try (TestCollection collection = new TestCollection("java java", "and java", "and")) {
            checkPhrase(collection.engine, "java", "a.txt", "b.txt");
            checkPhrase(collection.engine, "and", "b.txt", "c.txt");
        }
    }

    private static void testNoMatches() throws IOException {
        checkPhrase(new SearchEngine(), "java");
        try (TestCollection collection = new TestCollection("java", "search", "guide")) {
            for (String phrase : List.of("", "   ", "!?,--", "unknown", "java unknown", "java guide", "\u09ac\u09be\u0982\u09b2\u09be")) {
                checkPhrase(collection.engine, phrase);
            }
        }
    }

    private static void testResultOrder() throws IOException {
        try (TestCollection collection = new TestCollection("java search java search", "java search", "search java search")) {
            checkPhrase(collection.engine, "java search", "a.txt", "b.txt", "c.txt");
            checkPhrase(collection.engine, "java search", "a.txt", "b.txt", "c.txt");
            List<Document> matches = collection.engine.searchPhrase("java search");
            for (int i = 0; i < matches.size(); i++) {
                check(matches.get(i).getId() == i + 1, "Return the indexed Document IDs");
            }
        }
    }

    private static void testDocumentBoundaries() throws IOException {
        try (TestCollection collection = new TestCollection("java", "search", "java guide search")) {
            checkPhrase(collection.engine, "java search");
            checkPhrase(collection.engine, "search java");
        }
    }

    private static void testConsistentStart() throws IOException {
        try (TestCollection collection = new TestCollection("java search guide", "java search pause search guide", "java pause search guide")) {
            // Document b has both adjacent pairs, but no common complete phrase start.
            checkPhrase(collection.engine, "java search guide", "a.txt");
        }
    }

    private static void testNoFileScan() throws IOException {
        try (TestCollection collection = new TestCollection("java search", "network", "guide")) {
            for (Path file : collection.files) {
                Files.delete(file);
            }
            checkPhrase(collection.engine, "java search", "a.txt");
            checkPhrase(collection.engine, "search java");
        }
    }

    private static void testRebuild() throws IOException {
        try (TestCollection collection = new TestCollection("java search", "search java", "network")) {
            SearchEngine engine = collection.engine;
            engine.buildIndex(collection.directory.toString());
            checkPhrase(engine, "java search", "a.txt");
            Files.writeString(collection.files.get(0), "search java", StandardCharsets.UTF_8);
            engine.buildIndex(collection.directory.toString());
            checkPhrase(engine, "java search");
            checkPhrase(engine, "search java", "a.txt", "b.txt");
            Files.writeString(collection.files.get(0), "java search", StandardCharsets.UTF_8);
            Files.write(collection.files.get(1), new byte[] {(byte) 0xc3, (byte) 0x28});
            boolean failedRead = false;
            try {
                engine.buildIndex(collection.directory.toString());
            } catch (IOException expected) {
                failedRead = true;
            }
            check(failedRead, "Invalid UTF-8 must fail rebuild");
            checkPhrase(engine, "search java", "a.txt", "b.txt");
            checkPhrase(engine, "java search");
        }
    }

    private static void testSampleCollection() throws IOException {
        SearchEngine engine = new SearchEngine();
        engine.buildIndex("data/phrase_samples");
        check(engine.getDocumentCount() == 9, "Expected the nine fictional sample documents");
        checkPhrase(engine, "java search", "information_retrieval_workshop.txt", "java_programming_lab.txt");
        checkPhrase(engine, "search java", "reverse_order_exercise.txt");
        checkPhrase(engine, "java and java", "java_programming_lab.txt");
        checkPhrase(engine, "java java");
        checkPhrase(engine, "project presentation", "academic_announcement.txt", "project_presentation.txt");
        checkPhrase(engine, "in the seminar room", "academic_announcement.txt", "project_presentation.txt");
        checkPhrase(engine, "in the", "academic_announcement.txt", "information_retrieval_workshop.txt", "project_presentation.txt");
        checkPhrase(engine, "error error", "java_programming_lab.txt");
        checkPhrase(engine, "another error", "software_engineering_coursework.txt");
        checkPhrase(engine, "software engineering", "project_presentation.txt", "software_engineering_coursework.txt");
        checkPhrase(engine, "java programming", "java_programming_lab.txt");
        checkPhrase(engine, "programming in java", "seminar_discussion.txt");
        checkPhrase(engine, "binary search", "algorithms_coursework.txt");
        checkPhrase(engine, "java", "information_retrieval_workshop.txt", "java_programming_lab.txt",
                "networking_lab.txt", "reverse_order_exercise.txt", "seminar_discussion.txt");
        checkPhrase(engine, "network delays", "networking_lab.txt");
        checkPhrase(engine, "database login");
    }

    private static void testKeywordCompatibility() throws IOException {
        try (TestCollection collection = new TestCollection("java and search", "search", "java java java")) {
            SearchEngine engine = collection.engine;
            double idf = Math.log(4.0 / 3.0) + 1.0;
            for (int pass = 0; pass < 2; pass++) {
                checkKeyword(engine, "java", List.of("c.txt", "a.txt"), 3.0 * idf, idf);
                checkKeyword(engine, "search", List.of("a.txt", "b.txt"), idf, idf);
                for (String query : List.of("java search", "\"java search\"", "JAVA,java and search")) {
                    checkKeyword(engine, query, List.of("c.txt", "a.txt", "b.txt"), 3.0 * idf, idf + idf, idf);
                }
                check(engine.getDocumentCount() == 3 && engine.getUniqueTermCount() == 2
                        && engine.getTotalTokens() == 6, "Phrase queries cannot change index statistics");
                checkPhrase(engine, "java search");
                checkPhrase(engine, "java and search", "a.txt");
                checkPhrase(engine, "java java", "c.txt");
            }
        }
    }

    private static void testDefaultCorpus() throws IOException {
        SearchEngine engine = new SearchEngine();
        engine.buildIndex("data");
        check(engine.getDocumentCount() == 6, "Nested phrase samples must not join the default corpus");
        check(engine.getUniqueTermCount() == 75 && engine.getTotalTokens() == 120, "Default statistics unchanged");
        double idf = Math.log(7.0 / 3.0) + 1.0;
        checkKeyword(engine, "java", List.of("java_programming.txt", "academic_notice.txt"), 3.0 * idf, idf);
        checkPhrase(engine, "java and java");
        checkKeyword(engine, "java", List.of("java_programming.txt", "academic_notice.txt"), 3.0 * idf, idf);
    }

    private static void checkKeyword(SearchEngine engine, String query, List<String> filenames, double... scores) {
        List<SearchResult> actual = engine.search(query);
        check(actual.size() == filenames.size(), "Keyword result count for " + query);
        for (int i = 0; i < actual.size(); i++) {
            check(actual.get(i).getDocument().getFilename().equals(filenames.get(i)), "Keyword filename/order");
            check(Double.doubleToLongBits(actual.get(i).getScore()) == Double.doubleToLongBits(scores[i]),
                    "Exact keyword score at " + i + " for " + query);
        }
    }

    private static class TestCollection implements AutoCloseable {
        private final Path directory;
        private final List<Path> files;
        private final SearchEngine engine = new SearchEngine();

        private TestCollection(String first, String second, String third) throws IOException {
            directory = Files.createTempDirectory("iit-phrase-tests-");
            files = List.of(directory.resolve("a.txt"), directory.resolve("b.txt"), directory.resolve("c.txt"));
            List<String> texts = List.of(first, second, third);
            try {
                // Create in reverse order to ensure loading, not creation order, determines results.
                for (int i = files.size() - 1; i >= 0; i--) {
                    Files.writeString(files.get(i), texts.get(i), StandardCharsets.UTF_8);
                }
                engine.buildIndex(directory.toString());
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
            // Delete only the named files and this test's owned temporary directory.
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
