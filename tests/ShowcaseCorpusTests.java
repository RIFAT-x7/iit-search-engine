import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Corpus-specific expectations; the original six suites remain independent. */
public class ShowcaseCorpusTests {
    private static int passed;
    private static int failed;
    private static final Path ROOT = Path.of("data", "showcase");
    private static final Set<String> STOP = Set.of("a", "an", "the", "and", "or", "is", "are", "of", "to", "in", "for", "with");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");

    @FunctionalInterface
    private interface Check { void run() throws Exception; }
    private static void run(String label, Check check) {
        try { check.run(); passed++; System.out.println("PASS: " + label); }
        catch (Exception | AssertionError error) {
            failed++; System.out.println("FAIL: " + label + " - " + error);
            error.printStackTrace(System.out);
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
    private static List<String> names(List<Document> documents) {
        return documents.stream().map(Document::getFilename).toList();
    }
    private static List<String> rankedNames(List<SearchResult> results) {
        return results.stream().map(result -> result.getDocument().getFilename()).toList();
    }
    private static void phrase(SearchEngine engine, String query, String... expected) {
        require(names(engine.searchPhrase(query)).equals(List.of(expected)), "Phrase mismatch: " + query);
    }

    public static void main(String[] args) throws Exception {
        SearchEngine engine = new SearchEngine();
        engine.buildIndex(ROOT.toString());
        List<Path> files;
        try (var entries = Files.list(ROOT)) {
            files = entries.filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted().toList();
        }
        // Independent regex scan of raw files checks the production manual scanner and statistics.
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        Set<String> vocabulary = new HashSet<>();
        int total = 0;
        for (Path file : files) {
            Map<String, Integer> frequency = new HashMap<>();
            var matcher = TOKEN.matcher(Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String term = matcher.group();
                if (!STOP.contains(term)) { frequency.merge(term, 1, Integer::sum); vocabulary.add(term); total++; }
            }
            counts.put(file.getFileName().toString(), frequency);
        }
        final int processed = total;
        run("Exactly 25 immediate fictional text documents, excluding README", () -> {
            require(files.size() == 25 && engine.getDocumentCount() == 25, "Document count");
            require(engine.getIndexedDocumentBytes("README.md") == null, "README indexed");
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                int words = text.strip().split("\\s+").length;
                require(words >= 150 && words <= 250, "Word range: " + file);
                require(text.contains("Fictional study material; not an official university document."), "Provenance");
            }
        });
        run("Measured vocabulary and processed-token statistics match independent scan", () -> {
            require(vocabulary.size() == 1336 && processed == 3808, "Corpus golden statistics");
            require(engine.getUniqueTermCount() == vocabulary.size() && engine.getTotalTokens() == processed, "Engine statistics");
        });
        run("One-term Java matches and raw-TF smoothed-IDF scores", () -> {
            var results = engine.search("java");
            require(results.size() == 23, "Java document frequency");
            double idf = Math.log(26.0 / 24.0) + 1.0;
            for (var result : results) {
                double expected = counts.get(result.getDocument().getFilename()).get("java") * idf;
                require(Math.abs(expected - result.getScore()) < 1e-12, "Java score");
            }
            require(rankedNames(results).subList(0, 5).equals(List.of("java_programming_practice.txt", "phrase_order_exercise.txt",
                    "boolean_query_workshop.txt", "laboratory_guidelines.txt", "search_indexing_notes.txt")), "Java top five");
        });
        run("Multiword ranked OR retrieval matches an independent score oracle", () -> {
            List<SearchResult> expected = new ArrayList<>();
            for (Path file : files) {
                String name = file.getFileName().toString();
                double score = 0;
                for (String term : List.of("java", "search")) {
                    long df = counts.values().stream().filter(map -> map.containsKey(term)).count();
                    score += counts.get(name).getOrDefault(term, 0) * (Math.log(26.0 / (df + 1.0)) + 1);
                }
                if (score > 0) { expected.add(new SearchResult(new Document(0, name), score)); }
            }
            expected.sort(Comparator.comparingDouble(SearchResult::getScore).reversed()
                    .thenComparing(result -> result.getDocument().getFilename()));
            var actual = engine.search("java search");
            require(actual.size() == 25 && rankedNames(actual).equals(rankedNames(expected)), "Independent ordering");
            for (int i = 0; i < actual.size(); i++) {
                require(Math.abs(actual.get(i).getScore() - expected.get(i).getScore()) < 1e-12, "Multiword score");
            }
        });
        run("Equal software scores break ties by filename", () -> {
            var results = engine.search("software");
            require(results.size() == 25, "Enough matches for Top 10");
            require(rankedNames(results).subList(0, 4).equals(List.of("algorithms_clinic.txt", "heap_ranking_notes.txt",
                    "project_planning_board.txt", "software_testing_workshop.txt")), "Tie order");
            require(results.subList(0, 4).stream().allMatch(result -> result.getScore() == 2.0), "Tied scores");
        });
        for (int k : new int[] {1, 3, 5, 10, 100, Integer.MAX_VALUE}) {
            run("Top " + k + " is the exact unrestricted prefix", () -> {
                for (String query : List.of("java", "software", "java search", "networking security", "zzzznomatchingterm")) {
                    var all = engine.search(query);
                    var limited = engine.searchTopK(query, k);
                    require(limited.size() == Math.min(k, all.size()), "Limit");
                    for (int i = 0; i < limited.size(); i++) {
                        require(limited.get(i).getDocument().getFilename().equals(all.get(i).getDocument().getFilename()), "Prefix name");
                        require(Double.doubleToLongBits(limited.get(i).getScore()) == Double.doubleToLongBits(all.get(i).getScore()), "Score bits");
                    }
                }
            });
        }
        run("Complete phrase distinguishes separated and reversed tokens", () -> {
            phrase(engine, "java search", "information_retrieval_studio.txt", "java_programming_practice.txt", "search_indexing_notes.txt");
            phrase(engine, "search java", "phrase_order_exercise.txt");
            require(names(engine.searchBoolean("java AND search")).contains("networking_packet_lab.txt"), "Separated fixture absent");
            phrase(engine, "JaVa, SEARCH!", "information_retrieval_studio.txt", "java_programming_practice.txt", "search_indexing_notes.txt");
        });
        run("Stop words retain significant phrase positions", () -> {
            phrase(engine, "in the reading room", "library_reading_circle.txt", "research_seminar.txt");
            phrase(engine, "in reading room");
            phrase(engine, "java and programming", "boolean_query_workshop.txt", "phrase_order_exercise.txt");
            phrase(engine, "java programming", "boolean_query_workshop.txt", "java_programming_practice.txt");
        });
        run("Boolean intersection, union and grouped AND precedence", () -> {
            require(names(engine.searchBoolean("java AND programming")).equals(List.of("boolean_query_workshop.txt",
                    "java_programming_practice.txt", "phrase_order_exercise.txt")), "AND intersection");
            require(names(engine.searchBoolean("networking OR security")).equals(List.of("boolean_query_workshop.txt",
                    "cybersecurity_tabletop.txt", "database_catalogue.txt", "networking_packet_lab.txt",
                    "operating_systems_notes.txt", "software_testing_workshop.txt")), "OR union");
            require(names(engine.searchBoolean("java AND programming OR computer AND networking")).equals(List.of(
                    "boolean_query_workshop.txt", "java_programming_practice.txt", "networking_packet_lab.txt", "phrase_order_exercise.txt")), "Grouped precedence");
        });
        run("Unknown terms and keyword stop words remain empty", () -> {
            require(engine.search("zzzznomatchingterm").isEmpty(), "Unknown keyword");
            require(engine.searchPhrase("zzzznomatchingterm").isEmpty(), "Unknown phrase");
            require(engine.searchBoolean("zzzznomatchingterm").isEmpty(), "Unknown Boolean");
            require(engine.search("the and or in").isEmpty(), "Keyword stop words");
        });
        run("Every snapshot equals original bytes and returned arrays are defensive", () -> {
            for (Path file : files) {
                String name = file.getFileName().toString();
                byte[] original = Files.readAllBytes(file);
                byte[] returned = engine.getIndexedDocumentBytes(name);
                require(Arrays.equals(original, returned), "Original bytes: " + name);
                returned[0] ^= 1;
                require(Arrays.equals(original, engine.getIndexedDocumentBytes(name)), "Defensive copy");
            }
        });
        run("All three collections remain isolated and preserve original statistics", () -> {
            for (String folder : List.of("data", "data/phrase_samples")) {
                SearchEngine other = new SearchEngine(); other.buildIndex(folder);
                require(other.getDocumentCount() == (folder.equals("data") ? 6 : 9), "Original count");
                require(other.getUniqueTermCount() == (folder.equals("data") ? 75 : 190), "Original vocabulary");
                require(other.getTotalTokens() == (folder.equals("data") ? 120 : 328), "Original tokens");
                for (Path file : files) { require(other.getIndexedDocumentBytes(file.getFileName().toString()) == null, "Showcase leaked"); }
                try (var entries = Files.list(Path.of(folder))) {
                    for (Path file : entries.filter(path -> path.toString().endsWith(".txt")).toList()) {
                        require(engine.getIndexedDocumentBytes(file.getFileName().toString()) == null, "Original collection leaked");
                    }
                }
            }
        });
        run("Unknown and path-shaped snapshot lookups never expose documents", () -> {
            for (String name : List.of("unknown.txt", "../README.md", "data/showcase/java_programming_practice.txt", ROOT.toAbsolutePath().resolve("java_programming_practice.txt").toString())) {
                require(engine.getIndexedDocumentBytes(name) == null, "Unexpected lookup: " + name);
            }
        });
        System.out.println("Showcase Java: total passed: " + passed + "; total failed: " + failed);
        if (failed > 0) { System.exit(1); }
    }
}
