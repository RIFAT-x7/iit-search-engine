import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InvertedIndex {
    // term -> (document ID -> number of occurrences in that document)
    // Example: java -> {2=3, 4=1} means three occurrences in doc 2 and one in doc 4.
    private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();
    // term -> (document ID -> ascending zero-based token positions).
    // Stop words occupy positions and have entries here, but not in searchable postings.
    private final Map<String, Map<Integer, List<Integer>>> positionalPostings = new HashMap<>();
    private final Map<Integer, Integer> documentLengths = new HashMap<>();
    private int totalTokens = 0;

    public void addDocument(Document document, List<String> tokens) {
        // Legacy callers supply already processed tokens. Count every supplied token
        // as before; positions can only describe that supplied sequence.
        indexTokens(document, tokens, false);
    }

    // Supply TextProcessor.tokenize(text), before stop-word filtering, to retain
    // original positions. The frequency map still excludes the configured stop words.
    public void addDocumentWithPositions(Document document, List<String> normalizedTokens) {
        indexTokens(document, normalizedTokens, true);
    }

    private void indexTokens(Document document, List<String> tokens, boolean filterStopWords) {
        int documentId = document.getId();
        // Repeated additions append to the same document, preserving legacy TF counts.
        int position = documentLengths.getOrDefault(documentId, 0);
        for (String term : tokens) {
            positionalPostings.computeIfAbsent(term, key -> new HashMap<>())
                    .computeIfAbsent(documentId, key -> new ArrayList<>()).add(position++);
            if (filterStopWords && TextProcessor.isStopWord(term)) {
                continue;
            }
            Map<Integer, Integer> frequencies = postings.get(term);
            if (frequencies == null) {
                frequencies = new HashMap<>();
                postings.put(term, frequencies);
            }
            int previousCount = frequencies.getOrDefault(document.getId(), 0);
            frequencies.put(document.getId(), previousCount + 1);
            totalTokens++;
        }
        documentLengths.put(documentId, position);
    }

    /** Read-only snapshot for an already normalized term; absent terms return an empty map. */
    public Map<Integer, List<Integer>> getPositionalPostings(String term) {
        Map<Integer, List<Integer>> positions = positionalPostings.get(term);
        if (positions == null) {
            return Collections.emptyMap();
        }
        Map<Integer, List<Integer>> snapshot = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : positions.entrySet()) {
            snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<Integer, Integer> getPostings(String term) {
        Map<Integer, Integer> frequencies = postings.get(term);
        if (frequencies == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(frequencies);
    }

    public int getUniqueTermCount() { return postings.size(); }
    public int getTotalTokens() { return totalTokens; }
}
