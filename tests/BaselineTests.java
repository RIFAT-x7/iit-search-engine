import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BaselineTests {
    private static int passed = 0;
    private static int failed = 0;
    private static final double TOLERANCE = 0.000000001;

    public static void main(String[] args) {
        runTest("Test 1 - Text preprocessing", BaselineTests::testPreprocessing);
        runTest("Test 2 - Inverted-index correctness", BaselineTests::testInvertedIndex);
        runTest("Test 3 - Keyword retrieval", BaselineTests::testKeywordRetrieval);
        runTest("Test 4 - Ranking", BaselineTests::testRanking);
        runTest("Test 5 - Empty and unknown queries", BaselineTests::testEmptyQueries);
        runTest("Test 6 - Rebuild correctness", BaselineTests::testRebuild);
        runTest("Test 7 - Failed rebuild preservation", BaselineTests::testFailedRebuild);

        System.out.println("Total passed: " + passed);
        System.out.println("Total failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // This interface lets each test report checked exceptions, such as IOException.
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

    // Explicit checks work without the JVM's optional -ea flag.
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkScore(double expected, double actual, String message) {
        check(Math.abs(expected - actual) <= TOLERANCE,
                message + ": expected " + expected + ", actual " + actual);
    }

    private static void testPreprocessing() {
        TextProcessor processor = new TextProcessor();
        check(processor.process("JaVa SEARCH").equals(List.of("java", "search")),
                "Words should be lowercase");
        check(processor.process("java,search-engine.test").equals(
                List.of("java", "search", "engine", "test")),
                "Punctuation should separate words");
        check(processor.process("a an the and or is are of to in for with JAVA")
                .equals(List.of("java")), "All configured stop words should be removed");
        check(processor.process("Java 25 2026").equals(List.of("java", "25", "2026")),
                "Numeric tokens should be preserved");
    }

    private static void testInvertedIndex() {
        InvertedIndex index = new InvertedIndex();
        index.addDocument(new Document(1, "first.txt"), List.of("java", "java", "search"));
        index.addDocument(new Document(2, "second.txt"), List.of("java", "network"));

        check(index.getPostings("java").equals(Map.of(1, 2, 2, 1)),
                "Java frequencies should be 2 in document 1 and 1 in document 2");
        check(index.getPostings("search").equals(Map.of(1, 1)),
                "Search should occur once in document 1 only");
        check(index.getPostings("network").equals(Map.of(2, 1)),
                "Network should occur once in document 2 only");
        check(index.getPostings("unknown").isEmpty(), "Unknown terms should have no postings");
        check(index.getUniqueTermCount() == 3, "Expected 3 unique terms");
        check(index.getTotalTokens() == 5, "Expected 5 processed tokens");
    }

    private static void testKeywordRetrieval() throws IOException {
        try (TestCollection collection = new TestCollection()) {
            SearchEngine engine = new SearchEngine();
            engine.buildIndex(collection.directory.toString());
            List<SearchResult> results = engine.search("java");
            Set<String> filenames = new HashSet<>();
            for (SearchResult result : results) {
                filenames.add(result.getDocument().getFilename());
            }
            check(results.size() == 2, "Expected exactly 2 matching documents");
            check(filenames.equals(Set.of("a_once.txt", "z_often.txt")),
                    "Only the two documents containing java should match");
        }
    }

    private static void testRanking() throws IOException {
        try (TestCollection collection = new TestCollection()) {
            SearchEngine engine = new SearchEngine();
            engine.buildIndex(collection.directory.toString());
            List<SearchResult> results = engine.search("java");
            check(results.size() == 2, "Expected 2 ranked results");
            check(results.get(0).getDocument().getFilename().equals("z_often.txt"),
                    "The document with higher term frequency should rank first");
            check(results.get(1).getDocument().getFilename().equals("a_once.txt"),
                    "The document with lower term frequency should rank second");
            check(results.get(0).getScore() > results.get(1).getScore(),
                    "Scores should be in descending order");

            // N = 3 documents; df(java) = 2. Raw TF values are 3 and 1.
            double expectedIdf = Math.log(4.0 / 3.0) + 1.0;
            checkScore(3.0 * expectedIdf, results.get(0).getScore(), "Higher TF-IDF score");
            checkScore(expectedIdf, results.get(1).getScore(), "Lower TF-IDF score");
        }
    }

    private static void testEmptyQueries() throws IOException {
        try (TestCollection collection = new TestCollection()) {
            SearchEngine engine = new SearchEngine();
            engine.buildIndex(collection.directory.toString());
            check(engine.search("").isEmpty(), "Empty query should return no results");
            check(engine.search("   ").isEmpty(), "Whitespace query should return no results");
            check(engine.search("missingword").isEmpty(), "Unknown term should return no results");
            check(engine.search("the AND or in").isEmpty(),
                    "Stop-word-only query should return no results");
        }
    }

    private static void checkCollectionCounts(SearchEngine engine) {
        check(engine.getDocumentCount() == 3, "Expected 3 indexed documents");
        check(engine.getUniqueTermCount() == 3, "Expected 3 unique terms");
        check(engine.getTotalTokens() == 6, "Expected 6 processed tokens");
    }

    private static void testRebuild() throws IOException {
        try (TestCollection collection = new TestCollection()) {
            SearchEngine engine = new SearchEngine();
            engine.buildIndex(collection.directory.toString());
            checkCollectionCounts(engine);
            List<SearchResult> before = engine.search("java");
            check(before.size() == 2, "Expected 2 results before rebuilding");

            engine.buildIndex(collection.directory.toString());
            checkCollectionCounts(engine);
            List<SearchResult> after = engine.search("java");
            checkSameResults(before, after);
        }
    }

    private static void testFailedRebuild() throws IOException {
        try (TestCollection collection = new TestCollection()) {
            SearchEngine engine = new SearchEngine();
            engine.buildIndex(collection.directory.toString());
            checkCollectionCounts(engine);
            List<SearchResult> before = engine.search("java");
            check(before.size() == 2, "Expected a usable index before the failed rebuild");

            Path missingFolder = collection.directory.resolve("nonexistent-folder");
            check(Files.notExists(missingFolder), "Failure fixture must not exist");
            boolean caughtIOException = false;
            try {
                engine.buildIndex(missingFolder.toString());
            } catch (IOException expected) {
                caughtIOException = true;
            }
            check(caughtIOException, "A nonexistent folder should cause IOException");
            checkCollectionCounts(engine);
            checkSameResults(before, engine.search("java"));
        }
    }

    private static void checkSameResults(List<SearchResult> before, List<SearchResult> after) {
        check(after.size() == before.size(), "Rebuild should preserve the result count");
        for (int i = 0; i < before.size(); i++) {
            check(before.get(i).getDocument().getFilename().equals(
                    after.get(i).getDocument().getFilename()),
                    "Rebuild should preserve document order at result " + i);
            checkScore(before.get(i).getScore(), after.get(i).getScore(),
                    "Rebuild should preserve the score at result " + i);
        }
    }

    // Each test owns a new directory, separate from the repository's sample data.
    // try-with-resources calls close() even when a check fails.
    private static class TestCollection implements AutoCloseable {
        private final Path directory;
        private final List<Path> files;

        private TestCollection() throws IOException {
            directory = Files.createTempDirectory("iit-baseline-tests-");
            files = List.of(directory.resolve("a_once.txt"), directory.resolve("b_other.txt"),
                    directory.resolve("z_often.txt"));
            try {
                Files.writeString(files.get(0), "java guide", StandardCharsets.UTF_8);
                Files.writeString(files.get(1), "network", StandardCharsets.UTF_8);
                Files.writeString(files.get(2), "java java java", StandardCharsets.UTF_8);
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
            // Delete only our three named fixtures; never traverse or recursively delete.
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
