import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        SearchEngine engine = new SearchEngine();
        System.out.println("Fictional demonstration data - not official IIT documents.");
        rebuild(engine);

        try (Scanner input = new Scanner(System.in)) {
            while (true) {
                System.out.println("\n==============================");
                System.out.println("Mini IIT Search Prototype v0.1");
                System.out.println("==============================");
                System.out.println("1. Build/Rebuild Index");
                System.out.println("2. Search");
                System.out.println("3. Show Index Statistics");
                System.out.println("4. Exit");
                System.out.print("Choose an option: ");
                if (!input.hasNextLine()) { break; }
                String choice = input.nextLine().trim();
                switch (choice) {
                    case "1":
                        rebuild(engine);
                        break;
                    case "2":
                        System.out.print("Enter keywords: ");
                        if (!input.hasNextLine()) { return; }
                        String query = input.nextLine();
                        List<SearchResult> results = engine.search(query);
                        System.out.println("\nSearch query: " + query);
                        if (results.isEmpty()) {
                            System.out.println("No results found. Use keywords from the data files.");
                        } else {
                            System.out.println("Results:");
                            for (int i = 0; i < results.size(); i++) {
                                SearchResult result = results.get(i);
                                System.out.printf(Locale.ROOT, "%d. %s  Score: %.2f%n",
                                        i + 1, result.getDocument().getFilename(), result.getScore());
                            }
                        }
                        break;
                    case "3":
                        printStatistics(engine);
                        break;
                    case "4":
                        System.out.println("Goodbye!");
                        return;
                    default:
                        System.out.println("Please choose 1, 2, 3, or 4.");
                }
            }
        }
    }

    private static void rebuild(SearchEngine engine) {
        try {
            engine.buildIndex("data");
            System.out.println("Index built successfully.");
            printStatistics(engine);
        } catch (IOException exception) {
            System.out.println("Index build failed: " + exception.getMessage());
            System.out.println("Any previously built index has been kept.");
        }
    }

    private static void printStatistics(SearchEngine engine) {
        System.out.println("Indexed documents: " + engine.getDocumentCount());
        System.out.println("Unique terms: " + engine.getUniqueTermCount());
        System.out.println("Processed tokens: " + engine.getTotalTokens());
    }
}
