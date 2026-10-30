import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BooleanSearchTests {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        runTest("AND intersection", BooleanSearchTests::testAnd);
        runTest("OR union", BooleanSearchTests::testOr);
        runTest("Multiple AND operators", BooleanSearchTests::testMultipleAnd);
        runTest("Multiple OR operators", BooleanSearchTests::testMultipleOr);
        runTest("AND before OR in either position", BooleanSearchTests::testPrecedence);
        runTest("Case, punctuation, digits and whitespace", BooleanSearchTests::testNormalization);
        runTest("Repeated terms and duplicate elimination", BooleanSearchTests::testDuplicates);
        runTest("Stable filename order and indexed IDs", BooleanSearchTests::testOrder);
        runTest("Unknown terms in AND and OR groups", BooleanSearchTests::testUnknown);
        runTest("Single-term expressions", BooleanSearchTests::testSingleTerm);
        runTest("All configured stop words and reserved operators", BooleanSearchTests::testStopWords);
        runTest("Null, blank and missing operands", BooleanSearchTests::testMissingOperands);
        runTest("Consecutive operators", BooleanSearchTests::testConsecutiveOperators);
        runTest("Missing operators and multi-token operands", BooleanSearchTests::testMissingOperators);
        runTest("Unsupported grouping, NOT, quotes and wildcards", BooleanSearchTests::testUnsupportedSyntax);
        runTest("Empty index still validates expressions", BooleanSearchTests::testEmptyIndex);
        runTest("Retrieval does not read source files", BooleanSearchTests::testNoFileScan);
        runTest("Repeated, changed and failed rebuilds", BooleanSearchTests::testRebuild);
        runTest("Exact keyword scores, phrase results and statistics unchanged", BooleanSearchTests::testCompatibility);
        runTest("Original six-document collection", BooleanSearchTests::testDefaultCollection);
        runTest("Separate nine-document fictional collection", BooleanSearchTests::testSampleCollection);
        runTest("Document boundaries without positional requirements", BooleanSearchTests::testDocumentBoundaries);
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

    private static List<String> filenames(List<Document> documents) {
        List<String> names = new ArrayList<>();
        for (Document document : documents) {
            names.add(document.getFilename());
        }
        return names;
    }

    private static void checkBoolean(SearchEngine engine, String expression, String... expected) {
        List<String> actual = filenames(engine.searchBoolean(expression));
        check(actual.equals(List.of(expected)), "Boolean [" + expression + "]: expected "
                + List.of(expected) + ", actual " + actual);
    }

    private static void checkInvalid(SearchEngine engine, String expression, String messagePart) {
        try {
            engine.searchBoolean(expression);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains(messagePart),
                    "Expected descriptive error containing [" + messagePart + "] for [" + expression
                            + "]; actual: " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException for [" + expression + "]");
    }

    private static void testAnd() throws IOException {
        try (TestCollection collection = new TestCollection("java programming", "java networking", "programming networking")) {
            checkBoolean(collection.engine, "java AND programming", "a.txt");
            checkBoolean(collection.engine, "programming AND java", "a.txt");
            checkBoolean(collection.engine, "java AND networking", "b.txt");
        }
    }

    private static void testOr() throws IOException {
        try (TestCollection collection = new TestCollection("java", "programming", "java programming", "networking")) {
            checkBoolean(collection.engine, "java OR programming", "a.txt", "b.txt", "c.txt");
            checkBoolean(collection.engine, "programming OR java", "a.txt", "b.txt", "c.txt");
        }
    }

    private static void testMultipleAnd() throws IOException {
        try (TestCollection collection = new TestCollection("java programming laboratory", "java programming", "java laboratory", "programming laboratory")) {
            checkBoolean(collection.engine, "java AND programming AND laboratory", "a.txt");
            checkBoolean(collection.engine, "laboratory AND java AND programming", "a.txt");
            checkBoolean(collection.engine, "java AND programming AND laboratory AND absent");
        }
    }

    private static void testMultipleOr() throws IOException {
        try (TestCollection collection = new TestCollection("java", "networking", "algorithms", "design")) {
            checkBoolean(collection.engine, "java OR networking OR algorithms", "a.txt", "b.txt", "c.txt");
            checkBoolean(collection.engine, "absent OR java OR networking OR design", "a.txt", "b.txt", "d.txt");
        }
    }

    private static void testPrecedence() throws IOException {
        try (TestCollection collection = new TestCollection("java", "networking laboratory", "networking", "java programming laboratory", "programming")) {
            // A left-to-right evaluator would incorrectly discard a.txt in the first query.
            checkBoolean(collection.engine, "java OR networking AND laboratory", "a.txt", "b.txt", "d.txt");
            checkBoolean(collection.engine, "networking AND laboratory OR java", "a.txt", "b.txt", "d.txt");
            checkBoolean(collection.engine, "java AND programming OR networking AND laboratory", "b.txt", "d.txt");
            checkBoolean(collection.engine, "java OR absent AND programming OR networking AND laboratory", "a.txt", "b.txt", "d.txt");
            checkBoolean(collection.engine, "java AND absent OR programming AND laboratory", "d.txt");
        }
    }

    private static void testNormalization() throws IOException {
        try (TestCollection collection = new TestCollection("JAVA programming 25", "java network", "programming 25")) {
            checkBoolean(collection.engine, "JaVa, aNd PROGRAMMING.", "a.txt");
            checkBoolean(collection.engine, "  \t JAVA,\n oR NETWORK. \r\n", "a.txt", "b.txt");
            checkBoolean(collection.engine, "25 AND programming", "a.txt", "c.txt");
            checkBoolean(collection.engine, "25 OR network", "a.txt", "b.txt", "c.txt");
        }
    }

    private static void testDuplicates() throws IOException {
        try (TestCollection collection = new TestCollection("java java programming", "java", "programming")) {
            checkBoolean(collection.engine, "java AND java", "a.txt", "b.txt");
            checkBoolean(collection.engine, "java OR java OR programming OR java", "a.txt", "b.txt", "c.txt");
            checkBoolean(collection.engine, "java AND programming OR programming AND java", "a.txt");
        }
    }

    private static void testOrder() throws IOException {
        try (TestCollection collection = new TestCollection("java", "networking", "java java java")) {
            for (int pass = 0; pass < 3; pass++) {
                checkBoolean(collection.engine, "networking OR java", "a.txt", "b.txt", "c.txt");
                List<Document> results = collection.engine.searchBoolean("java OR networking");
                for (int i = 0; i < results.size(); i++) {
                    check(results.get(i).getId() == i + 1, "Return existing document IDs");
                }
                results.clear(); // A caller's result list must not mutate the index.
            }
        }
    }

    private static void testUnknown() throws IOException {
        try (TestCollection collection = new TestCollection("java", "networking")) {
            checkBoolean(collection.engine, "absent");
            checkBoolean(collection.engine, "java AND absent");
            checkBoolean(collection.engine, "absent AND java");
            checkBoolean(collection.engine, "absent OR java", "a.txt");
            checkBoolean(collection.engine, "java OR absent", "a.txt");
            checkBoolean(collection.engine, "absent OR java AND networking");
        }
    }

    private static void testSingleTerm() throws IOException {
        try (TestCollection collection = new TestCollection("java", "java java java", "programming")) {
            checkBoolean(collection.engine, "Java!", "a.txt", "b.txt");
            checkBoolean(collection.engine, "programming", "c.txt");
            checkBoolean(collection.engine, "absent");
        }
    }

    private static void testStopWords() throws IOException {
        try (TestCollection collection = new TestCollection("java and the programming")) {
            for (String stop : List.of("a", "an", "the", "is", "are", "of", "to", "in", "for", "with")) {
                checkInvalid(collection.engine, stop, "Stop-word operand");
                checkInvalid(collection.engine, "java AND " + stop, "Stop-word operand");
                checkInvalid(collection.engine, stop.toUpperCase(java.util.Locale.ROOT) + " OR java", "Stop-word operand");
            }
            for (String operator : List.of("AND", "OR", "and", "or")) {
                checkInvalid(collection.engine, operator, "Expected an operand");
                checkInvalid(collection.engine, "java OR " + operator, "Expected an operand");
            }
            checkInvalid(collection.engine, "java AND THE,", "Stop-word operand");
            checkInvalid(collection.engine, "absent AND the", "Stop-word operand");
        }
    }

    private static void testMissingOperands() {
        SearchEngine engine = new SearchEngine();
        checkInvalid(engine, null, "must not be null or blank");
        for (String expression : List.of("", " ", "\t\r\n")) {
            checkInvalid(engine, expression, "must not be null or blank");
        }
        for (String expression : List.of("AND java", "or java")) {
            checkInvalid(engine, expression, "Expected an operand");
        }
        for (String expression : List.of("java AND", "java OR", "absent AND", "java OR absent AND")) {
            checkInvalid(engine, expression, "Missing operand");
        }
        for (String expression : List.of("!!!", "java AND ...", "\u09ac\u09be\u0982\u09b2\u09be")) {
            checkInvalid(engine, expression, "normalize to one term");
        }
    }

    private static void testConsecutiveOperators() {
        SearchEngine engine = new SearchEngine();
        for (String expression : List.of("java AND AND programming", "java AND OR programming",
                "java OR AND programming", "java OR OR programming", "absent AND OR")) {
            checkInvalid(engine, expression, "Expected an operand");
        }
    }

    private static void testMissingOperators() {
        SearchEngine engine = new SearchEngine();
        for (String expression : List.of("java programming", "java AND programming networking", "java XOR programming")) {
            checkInvalid(engine, expression, "Expected AND or OR");
        }
        for (String expression : List.of("java-programming", "java,programming OR networking", "java AND the-programming")) {
            checkInvalid(engine, expression, "normalize to one term");
        }
    }

    private static void testUnsupportedSyntax() {
        SearchEngine engine = new SearchEngine();
        for (String expression : List.of("(java)", "java AND (programming OR networking)", "[java]", "{java}",
                "\"java\"", "\"java programming\"", "'java'", "java*", "jav?", "java&&programming", "java|programming")) {
            checkInvalid(engine, expression, "Unsupported Boolean syntax");
        }
        checkInvalid(engine, "java && programming", "Expected AND or OR");
        checkInvalid(engine, "java OR NOT", "NOT is not supported");
        checkInvalid(engine, "not", "NOT is not supported");
        checkInvalid(engine, "java NOT programming", "Expected AND or OR");
    }

    private static void testEmptyIndex() throws IOException {
        SearchEngine engine = new SearchEngine();
        checkBoolean(engine, "java");
        checkBoolean(engine, "java AND programming OR networking");
        checkInvalid(engine, "java OR", "Missing operand");
        try (TestCollection collection = new TestCollection()) {
            checkBoolean(collection.engine, "java OR networking AND programming");
            checkInvalid(collection.engine, "java AND the", "Stop-word operand");
        }
    }

    private static void testNoFileScan() throws IOException {
        try (TestCollection collection = new TestCollection("java programming", "java", "networking")) {
            for (Path file : collection.files) {
                Files.delete(file);
            }
            checkBoolean(collection.engine, "java AND programming", "a.txt");
            checkBoolean(collection.engine, "java OR networking", "a.txt", "b.txt", "c.txt");
            checkBoolean(collection.engine, "programming AND networking");
        }
    }

    private static void testRebuild() throws IOException {
        try (TestCollection collection = new TestCollection("java programming", "networking", "programming");
             TestCollection replacement = new TestCollection("java networking")) {
            SearchEngine engine = collection.engine;
            engine.buildIndex(collection.directory.toString());
            checkBoolean(engine, "java AND programming", "a.txt");
            Files.writeString(collection.files.get(0), "networking", StandardCharsets.UTF_8);
            Files.delete(collection.files.get(2));
            engine.buildIndex(collection.directory.toString());
            checkBoolean(engine, "java OR programming");
            checkBoolean(engine, "networking", "a.txt", "b.txt");
            check(engine.getDocumentCount() == 2, "Deleted document must leave the index");
            engine.buildIndex(replacement.directory.toString());
            checkBoolean(engine, "java AND networking", "a.txt");
            check(engine.getDocumentCount() == 1, "Rebuild replaces the old collection");
            Files.write(replacement.files.get(0), new byte[] {(byte) 0xc3, (byte) 0x28});
            boolean failedRead = false;
            try {
                engine.buildIndex(replacement.directory.toString());
            } catch (IOException expected) {
                failedRead = true;
            }
            check(failedRead, "Invalid UTF-8 must fail the rebuild");
            checkBoolean(engine, "java AND networking", "a.txt");
        }
    }

    private static void testCompatibility() throws IOException {
        try (TestCollection collection = new TestCollection("java and search", "search", "java java java")) {
            SearchEngine engine = collection.engine;
            Map<Integer, List<Integer>> positions = engine.getPositionalPostings("java");
            double idf = Math.log(4.0 / 3.0) + 1.0;
            for (int pass = 0; pass < 2; pass++) {
                for (String query : List.of("java search", "\"java search\"", "JAVA,java and search")) {
                    checkKeyword(engine, query, List.of("c.txt", "a.txt", "b.txt"), 3.0 * idf, idf + idf, idf);
                }
                checkKeyword(engine, "java AND search", List.of("c.txt", "a.txt", "b.txt"), 3.0 * idf, idf + idf, idf);
                check(filenames(engine.searchPhrase("java and search")).equals(List.of("a.txt")), "Phrase stop words unchanged");
                check(filenames(engine.searchPhrase("java java")).equals(List.of("c.txt")), "Phrase repetition unchanged");
                check(engine.searchPhrase("java search").isEmpty(), "Phrase adjacency unchanged");
                check(engine.getDocumentCount() == 3 && engine.getUniqueTermCount() == 2
                        && engine.getTotalTokens() == 6, "Index statistics unchanged");
                check(engine.getPositionalPostings("java").equals(positions), "Positional entries unchanged");
                checkBoolean(engine, "java AND search", "a.txt");
                checkBoolean(engine, "java OR search", "a.txt", "b.txt", "c.txt");
                checkInvalid(engine, "java AND the", "Stop-word operand");
            }
        }
    }

    private static void testDefaultCollection() throws IOException {
        SearchEngine engine = new SearchEngine();
        engine.buildIndex("data");
        checkBoolean(engine, "java AND programming", "java_programming.txt");
        checkBoolean(engine, "java OR networking", "academic_notice.txt", "java_programming.txt", "networking.txt");
        checkBoolean(engine, "java OR networking AND laboratory", "academic_notice.txt", "java_programming.txt", "networking.txt");
        checkBoolean(engine, "software AND engineering", "algorithms.txt", "software_engineering.txt");
        check(engine.getDocumentCount() == 6 && engine.getUniqueTermCount() == 75
                && engine.getTotalTokens() == 120, "Default corpus statistics unchanged");
        double idf = Math.log(7.0 / 3.0) + 1.0;
        checkKeyword(engine, "java", List.of("java_programming.txt", "academic_notice.txt"), 3.0 * idf, idf);
    }

    private static void testSampleCollection() throws IOException {
        SearchEngine engine = new SearchEngine();
        engine.buildIndex("data/phrase_samples");
        check(engine.getDocumentCount() == 9, "Load only the nine fictional documents");
        checkBoolean(engine, "java AND programming", "java_programming_lab.txt", "seminar_discussion.txt");
        checkBoolean(engine, "java AND search", "information_retrieval_workshop.txt", "java_programming_lab.txt",
                "networking_lab.txt", "reverse_order_exercise.txt");
        checkBoolean(engine, "networking AND laboratory", "networking_lab.txt");
        checkBoolean(engine, "project AND presentation", "academic_announcement.txt", "project_presentation.txt");
        checkBoolean(engine, "error AND error", "java_programming_lab.txt", "software_engineering_coursework.txt");
        checkBoolean(engine, "binary OR networking AND laboratory", "algorithms_coursework.txt", "networking_lab.txt");
        checkBoolean(engine, "database OR login");
        check(filenames(engine.searchPhrase("java search")).equals(List.of(
                "information_retrieval_workshop.txt", "java_programming_lab.txt")), "Demo phrase matching unchanged");
    }

    private static void testDocumentBoundaries() throws IOException {
        try (TestCollection collection = new TestCollection("java", "programming", "programming useful java")) {
            checkBoolean(collection.engine, "java AND programming", "c.txt");
            checkBoolean(collection.engine, "programming AND java", "c.txt");
            check(collection.engine.searchPhrase("java programming").isEmpty(), "Boolean AND needs no adjacency");
        }
    }

    private static void checkKeyword(SearchEngine engine, String query, List<String> names, double... scores) {
        List<SearchResult> results = engine.search(query);
        check(results.size() == names.size(), "Keyword result count for " + query);
        for (int i = 0; i < results.size(); i++) {
            check(results.get(i).getDocument().getFilename().equals(names.get(i)), "Keyword filename/order for " + query);
            check(Double.doubleToLongBits(results.get(i).getScore()) == Double.doubleToLongBits(scores[i]),
                    "Exact keyword score for " + query + " at " + i);
        }
    }

    private static class TestCollection implements AutoCloseable {
        private final Path directory;
        private final List<Path> files = new ArrayList<>();
        private final SearchEngine engine = new SearchEngine();

        private TestCollection(String... texts) throws IOException {
            directory = Files.createTempDirectory("iit-boolean-tests-");
            for (int i = 0; i < texts.length; i++) {
                files.add(directory.resolve((char) ('a' + i) + ".txt"));
            }
            try {
                // Creation order deliberately differs from filename order.
                for (int i = texts.length - 1; i >= 0; i--) {
                    Files.writeString(files.get(i), texts[i], StandardCharsets.UTF_8);
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
            IOException failure = null;
            // Only remove the named fixture files and this owned temporary folder.
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
