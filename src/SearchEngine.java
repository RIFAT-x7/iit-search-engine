import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SearchEngine {
    private final TextProcessor processor = new TextProcessor();
    private List<Document> documents = new ArrayList<>();
    private InvertedIndex index = new InvertedIndex();

    public void buildIndex(String folderPath) throws IOException {
        File folder = new File(folderPath);
        File[] files = folder.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (files == null) {
            throw new IOException("Cannot read data folder. Run from the prototype folder.");
        }
        Arrays.sort(files, Comparator.comparing(File::getName));

        // Build fresh structures so a failed read cannot leave a half-built index.
        List<Document> newDocuments = new ArrayList<>();
        InvertedIndex newIndex = new InvertedIndex();
        for (File file : files) {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Document document = new Document(newDocuments.size() + 1, file.getName());
            newDocuments.add(document);
            newIndex.addDocumentWithPositions(document, processor.tokenize(text));
        }
        documents = newDocuments;
        index = newIndex;
    }

    public List<SearchResult> search(String query) {
        // Count each distinct query term once; repetition does not inflate its weight.
        Set<String> queryTerms = new LinkedHashSet<>(processor.process(query));
        double[] scores = new double[documents.size() + 1];
        for (String term : queryTerms) {
            Map<Integer, Integer> postings = index.getPostings(term);
            if (postings.isEmpty()) {
                continue;
            }
            int documentFrequency = postings.size();
            // Smoothed IDF = ln((N + 1) / (df + 1)) + 1. TF is the raw word count.
            double idf = Math.log((documents.size() + 1.0)
                    / (documentFrequency + 1.0)) + 1.0;
            for (Map.Entry<Integer, Integer> posting : postings.entrySet()) {
                scores[posting.getKey()] += posting.getValue() * idf;
            }
        }

        List<SearchResult> results = new ArrayList<>();
        for (Document document : documents) {
            if (scores[document.getId()] > 0) {
                results.add(new SearchResult(document, scores[document.getId()]));
            }
        }
        // Manual insertion sort: largest score first; filename order breaks ties.
        // Initial result order is alphabetical because documents were loaded that way.
        for (int i = 1; i < results.size(); i++) {
            SearchResult current = results.get(i);
            int j = i - 1;
            while (j >= 0 && results.get(j).getScore() < current.getScore()) {
                results.set(j + 1, results.get(j));
                j--;
            }
            results.set(j + 1, current);
        }
        return results;
    }

    /**
     * Scores posting-list candidates only and retains at most k in a worst-first
     * binary heap. Scores and query-term addition order match search() exactly.
     * No full result sort or document-text scan is needed.
     *
     * @throws IllegalArgumentException when k is less than one
     */
    public List<SearchResult> searchTopK(String query, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("Top-K limit must be at least 1.");
        }
        Set<String> queryTerms = new LinkedHashSet<>(processor.process(query));
        Map<Integer, Double> scores = new HashMap<>();
        for (String term : queryTerms) {
            Map<Integer, Integer> postings = index.getPostings(term);
            if (postings.isEmpty()) {
                continue;
            }
            int documentFrequency = postings.size();
            double idf = Math.log((documents.size() + 1.0)
                    / (documentFrequency + 1.0)) + 1.0;
            for (Map.Entry<Integer, Integer> posting : postings.entrySet()) {
                int documentId = posting.getKey();
                scores.put(documentId, scores.getOrDefault(documentId, 0.0)
                        + posting.getValue() * idf);
            }
        }

        BoundedResultHeap heap = new BoundedResultHeap(k);
        for (Map.Entry<Integer, Double> candidate : scores.entrySet()) {
            if (candidate.getValue() > 0) {
                // buildIndex assigns contiguous IDs starting at 1, so metadata
                // lookup is O(1); no scan of all N documents is required.
                Document document = documents.get(candidate.getKey() - 1);
                heap.offer(new SearchResult(document, candidate.getValue()));
            }
        }
        List<SearchResult> results = new ArrayList<>();
        while (!heap.entries.isEmpty()) {
            results.add(heap.removeWorst());
        }
        // Extractions are worst-to-best. Reverse only the retained entries.
        Collections.reverse(results);
        return results;
    }

    private static class BoundedResultHeap {
        private final int limit;
        // Grow on demand rather than allocating k slots (k may be MAX_VALUE).
        private final List<SearchResult> entries = new ArrayList<>();

        private BoundedResultHeap(int limit) {
            this.limit = limit;
        }

        private static boolean isWorse(SearchResult left, SearchResult right) {
            if (left.getScore() != right.getScore()) {
                return left.getScore() < right.getScore();
            }
            return left.getDocument().getFilename()
                    .compareTo(right.getDocument().getFilename()) > 0;
        }

        private void offer(SearchResult candidate) {
            if (entries.size() < limit) {
                entries.add(candidate);
                siftUp(entries.size() - 1);
            } else if (isWorse(entries.get(0), candidate)) {
                entries.set(0, candidate);
                siftDown(0);
            }
        }

        private void siftUp(int child) {
            while (child > 0) {
                int parent = (child - 1) / 2;
                if (!isWorse(entries.get(child), entries.get(parent))) {
                    break;
                }
                swap(child, parent);
                child = parent;
            }
        }

        private void siftDown(int parent) {
            // A node has a child iff parent < size/2. This also avoids overflow
            // in 2*parent+1 even for an unusually large heap.
            while (parent < entries.size() / 2) {
                int worseChild = 2 * parent + 1;
                int right = worseChild + 1;
                if (right < entries.size()
                        && isWorse(entries.get(right), entries.get(worseChild))) {
                    worseChild = right;
                }
                if (!isWorse(entries.get(worseChild), entries.get(parent))) {
                    break;
                }
                swap(parent, worseChild);
                parent = worseChild;
            }
        }

        private SearchResult removeWorst() {
            SearchResult worst = entries.get(0);
            SearchResult last = entries.remove(entries.size() - 1);
            if (!entries.isEmpty()) {
                entries.set(0, last);
                siftDown(0);
            }
            return worst;
        }

        private void swap(int first, int second) {
            SearchResult saved = entries.get(first);
            entries.set(first, entries.get(second));
            entries.set(second, saved);
        }
    }

    /** Matches consecutive normalized tokens, including stop words, in filename order.
     * The input is phrase text; quotation marks have no query-language meaning.
     */
    public List<Document> searchPhrase(String phrase) {
        List<String> terms = processor.tokenize(phrase);
        List<Document> matches = new ArrayList<>();
        if (terms.isEmpty()) {
            return matches;
        }

        List<Map<Integer, List<Integer>>> phrasePostings = new ArrayList<>();
        for (String term : terms) {
            Map<Integer, List<Integer>> positions = index.getPositionalPostings(term);
            if (positions.isEmpty()) {
                return matches;
            }
            phrasePostings.add(positions);
        }

        // Documents were loaded in filename order. Check each ID independently;
        // even several phrase occurrences produce only one matching document.
        for (Document document : documents) {
            int documentId = document.getId();
            List<Integer> starts = phrasePostings.get(0).getOrDefault(documentId, List.of());
            for (int offset = 1; offset < terms.size() && !starts.isEmpty(); offset++) {
                List<Integer> positions = phrasePostings.get(offset)
                        .getOrDefault(documentId, List.of());
                starts = retainPhraseStarts(starts, positions, offset);
            }
            if (!starts.isEmpty()) {
                matches.add(document);
            }
        }
        return matches;
    }

    /**
     * Evaluates term (AND term)* groups joined by OR, without ranking.
     * Operators are standalone, whitespace-separated AND/OR (case-insensitive).
     * Each operand must normalize to exactly one ASCII-alphanumeric token using
     * the keyword rules. Surrounding punctuation is allowed, but stop words are
     * rejected rather than silently removed. AND/OR are reserved operators.
     * Parentheses, NOT, quoted operands and wildcards are not supported.
     *
     * @throws IllegalArgumentException for null, blank or malformed expressions
     */
    public List<Document> searchBoolean(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Boolean expression must not be null or blank.");
        }
        String[] parts = expression.strip().split("\\s+");
        Set<Integer> matches = new HashSet<>();
        Set<Integer> group = booleanOperandDocuments(parts[0]);

        // Finish each AND group before including it in the OR union. Never
        // short-circuit parsing: even an unknown term must not hide invalid input.
        for (int i = 1; i < parts.length; i += 2) {
            String operator = parts[i];
            if (!operator.equalsIgnoreCase("AND") && !operator.equalsIgnoreCase("OR")) {
                throw new IllegalArgumentException("Expected AND or OR, found: " + operator);
            }
            if (i + 1 == parts.length) {
                throw new IllegalArgumentException("Missing operand after " + operator + ".");
            }
            Set<Integer> next = booleanOperandDocuments(parts[i + 1]);
            if (operator.equalsIgnoreCase("AND")) {
                group = intersectDocuments(group, next);
            } else {
                includeDocuments(matches, group);
                group = next;
            }
        }
        includeDocuments(matches, group);

        // Document metadata is already in filename order; set iteration order
        // cannot affect results. No source text is read and no scores are used.
        List<Document> results = new ArrayList<>();
        for (Document document : documents) {
            if (matches.contains(document.getId())) {
                results.add(document);
            }
        }
        return results;
    }

    private Set<Integer> booleanOperandDocuments(String operand) {
        if (operand.equalsIgnoreCase("AND") || operand.equalsIgnoreCase("OR")) {
            throw new IllegalArgumentException("Expected an operand, found operator: " + operand);
        }
        if (operand.equalsIgnoreCase("NOT")) {
            throw new IllegalArgumentException("NOT is not supported in Boolean expressions.");
        }
        // Reject query syntax explicitly instead of treating it as punctuation.
        for (int i = 0; i < operand.length(); i++) {
            if ("()[]{}\"'*?&|".indexOf(operand.charAt(i)) >= 0) {
                throw new IllegalArgumentException("Unsupported Boolean syntax in operand: " + operand);
            }
        }
        List<String> terms = processor.tokenize(operand);
        if (terms.size() != 1) {
            throw new IllegalArgumentException("Operand must normalize to one term; separate terms"
                    + " with AND or OR: " + operand);
        }
        String term = terms.get(0);
        // Same stop-word rule as process(); no stop word may disappear from a
        // Boolean expression, since that would silently change its meaning.
        if (TextProcessor.isStopWord(term)) {
            throw new IllegalArgumentException("Stop-word operand is not searchable: " + operand);
        }
        Set<Integer> ids = new HashSet<>();
        for (Integer documentId : index.getPostings(term).keySet()) {
            ids.add(documentId);
        }
        return ids;
    }

    private Set<Integer> intersectDocuments(Set<Integer> left, Set<Integer> right) {
        Set<Integer> intersection = new HashSet<>();
        for (Integer documentId : left) {
            if (right.contains(documentId)) {
                intersection.add(documentId);
            }
        }
        return intersection;
    }

    private void includeDocuments(Set<Integer> target, Set<Integer> source) {
        for (Integer documentId : source) {
            target.add(documentId);
        }
    }

    // Merge two sorted lists, retaining each original start p only if this term
    // occurs at p + offset. Keeping the same start across all terms prevents
    // unrelated adjacent pairs from being mistaken for one complete phrase.
    private List<Integer> retainPhraseStarts(List<Integer> starts, List<Integer> positions,
                                             int offset) {
        List<Integer> retained = new ArrayList<>();
        int startIndex = 0;
        int positionIndex = 0;
        while (startIndex < starts.size() && positionIndex < positions.size()) {
            int expectedPosition = starts.get(startIndex) + offset;
            int actualPosition = positions.get(positionIndex);
            if (actualPosition < expectedPosition) {
                positionIndex++;
            } else if (actualPosition > expectedPosition) {
                startIndex++;
            } else {
                retained.add(starts.get(startIndex));
                startIndex++;
                positionIndex++;
            }
        }
        return retained;
    }

    public int getDocumentCount() { return documents.size(); }
    /** Read-only positional snapshot, keyed by document ID, for a normalized term. */
    public Map<Integer, List<Integer>> getPositionalPostings(String term) {
        return index.getPositionalPostings(term);
    }

    public int getUniqueTermCount() { return index.getUniqueTermCount(); }
    public int getTotalTokens() { return index.getTotalTokens(); }
}
