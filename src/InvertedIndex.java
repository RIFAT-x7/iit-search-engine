import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InvertedIndex {
    // term -> (document ID -> number of occurrences in that document)
    // Example: java -> {2=3, 4=1} means three occurrences in doc 2 and one in doc 4.
    private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();
    private int totalTokens = 0;

    public void addDocument(Document document, List<String> tokens) {
        for (String term : tokens) {
            Map<Integer, Integer> frequencies = postings.get(term);
            if (frequencies == null) {
                frequencies = new HashMap<>();
                postings.put(term, frequencies);
            }
            int previousCount = frequencies.getOrDefault(document.getId(), 0);
            frequencies.put(document.getId(), previousCount + 1);
            totalTokens++;
        }
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
