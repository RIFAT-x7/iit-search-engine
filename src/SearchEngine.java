import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
