import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class IndexedDocumentTests {
    private static int passed;
    private static int failed;
    private static int skipped;

    @FunctionalInterface
    private interface TestCase { void run() throws Exception; }

    private static void run(String name, TestCase test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS: " + name);
        } catch (Exception | AssertionError error) {
            failed++;
            System.out.println("FAIL: " + name + " - " + error);
            error.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static void equalBytes(byte[] expected, byte[] actual) {
        check(Arrays.equals(expected, actual), "Original bytes differ");
    }

    public static void main(String[] args) throws Exception {
        run("Original UTF-8 bytes, BOM and line endings", () -> {
            try (Fixture f = new Fixture()) {
                byte[] bytes = utf8("\ufeffJava\r\nBangla: \u09ac\u09be\u0982\u09b2\u09be & <text>\n");
                f.write("original.txt", bytes);
                f.build();
                equalBytes(bytes, f.engine.getIndexedDocumentBytes("original.txt"));
            }
        });
        run("Multiple documents and exact case-sensitive lookup", () -> {
            try (Fixture f = new Fixture()) {
                f.write("a.txt", utf8("java"));
                f.write("b.txt", utf8("python"));
                f.build();
                equalBytes(utf8("java"), f.engine.getIndexedDocumentBytes("a.txt"));
                equalBytes(utf8("python"), f.engine.getIndexedDocumentBytes("b.txt"));
                check(f.engine.getIndexedDocumentBytes("A.txt") == null, "Lookup must be exact");
            }
        });
        run("Unknown names and path-shaped lookups never read files", () -> {
            try (Fixture f = new Fixture()) {
                f.write("a.txt", utf8("java"));
                f.build();
                for (String name : new String[] {null, "", "missing.txt", "../a.txt", f.folder.resolve("a.txt").toString()}) {
                    check(f.engine.getIndexedDocumentBytes(name) == null, "Unknown lookup must return null");
                }
            }
        });
        run("Non-text files and nested directories are not snapshots", () -> {
            try (Fixture f = new Fixture()) {
                f.write("allowed.TXT", utf8("java"));
                f.write("private.md", utf8("secret"));
                Path nested = Files.createDirectory(f.folder.resolve("nested.txt"));
                f.owned.add(nested);
                f.build();
                check(f.engine.getDocumentCount() == 1, "Only immediate text files accepted");
                check(f.engine.getIndexedDocumentBytes("private.md") == null, "Unindexed file exposed");
                check(f.engine.getIndexedDocumentBytes("nested.txt") == null, "Directory exposed");
            }
        });
        run("Changing source bytes does not change the indexed snapshot", () -> {
            try (Fixture f = new Fixture()) {
                Path file = f.write("a.txt", utf8("java"));
                f.build();
                Files.write(file, utf8("changed python"));
                equalBytes(utf8("java"), f.engine.getIndexedDocumentBytes("a.txt"));
                check(f.engine.search("python").isEmpty(), "Search must use the same old index");
            }
        });
        run("Deleting source files does not remove snapshots", () -> {
            try (Fixture f = new Fixture()) {
                Path file = f.write("a.txt", utf8("java"));
                f.build();
                Files.delete(file);
                equalBytes(utf8("java"), f.engine.getIndexedDocumentBytes("a.txt"));
                check(f.engine.searchBoolean("java").size() == 1, "Indexed match disappeared");
            }
        });
        run("Successful rebuild refreshes content and removes old snapshots", () -> {
            try (Fixture f = new Fixture()) {
                Path file = f.write("a.txt", utf8("java"));
                f.build();
                Files.delete(file);
                f.write("b.txt", utf8("python"));
                f.build();
                check(f.engine.getIndexedDocumentBytes("a.txt") == null, "Old snapshot retained");
                equalBytes(utf8("python"), f.engine.getIndexedDocumentBytes("b.txt"));
                check(f.engine.search("java").isEmpty(), "Old index retained");
            }
        });
        run("Failed rebuild preserves search, ranking, positions and all snapshots", () -> {
            try (Fixture f = new Fixture()) {
                Path file = f.write("a.txt", utf8("java java programming"));
                f.build();
                double score = f.engine.search("java").get(0).getScore();
                var positions = f.engine.getPositionalPostings("java");
                Files.write(file, utf8("python"));
                f.write("z.txt", new byte[] {(byte) 0xc3, 0x28});
                try { f.build(); throw new AssertionError("Invalid UTF-8 rebuild succeeded"); }
                catch (IOException expected) { }
                equalBytes(utf8("java java programming"), f.engine.getIndexedDocumentBytes("a.txt"));
                check(f.engine.getIndexedDocumentBytes("z.txt") == null, "Partial snapshot published");
                check(f.engine.search("java").get(0).getScore() == score, "Ranking changed");
                check(f.engine.getPositionalPostings("java").equals(positions), "Positions changed");
                check(f.engine.searchPhrase("java programming").size() == 1, "Phrase index changed");
                check(f.engine.searchBoolean("java AND programming").size() == 1, "Boolean index changed");
            }
        });
        run("Returned byte arrays are defensive copies", () -> {
            try (Fixture f = new Fixture()) {
                f.write("a.txt", utf8("java")); f.build();
                byte[] first = f.engine.getIndexedDocumentBytes("a.txt");
                first[0] = 'X';
                equalBytes(utf8("java"), f.engine.getIndexedDocumentBytes("a.txt"));
            }
        });
        run("Safe spaces, percent, plus, ampersand and Unicode filenames", () -> {
            try (Fixture f = new Fixture()) {
                String name = "notes + 50% & \u09ac.txt";
                f.write(name, utf8("java")); f.build();
                equalBytes(utf8("java"), f.engine.getIndexedDocumentBytes(name));
            }
        });
        run("Engine instances and successful collection switches are isolated", () -> {
            try (Fixture a = new Fixture(); Fixture b = new Fixture()) {
                a.write("same.txt", utf8("java")); b.write("same.txt", utf8("python"));
                a.build(); b.build();
                equalBytes(utf8("java"), a.engine.getIndexedDocumentBytes("same.txt"));
                equalBytes(utf8("python"), b.engine.getIndexedDocumentBytes("same.txt"));
                a.engine.buildIndex(b.folder.toString());
                equalBytes(utf8("python"), a.engine.getIndexedDocumentBytes("same.txt"));
            }
        });
        run("Empty files and sorted document IDs remain valid", () -> {
            try (Fixture f = new Fixture()) {
                f.write("z.txt", utf8("java")); f.write("a.txt", utf8("java")); f.write("empty.txt", new byte[0]);
                f.build();
                equalBytes(new byte[0], f.engine.getIndexedDocumentBytes("empty.txt"));
                var results = f.engine.searchBoolean("java");
                check(results.get(0).getId() == 1 && results.get(0).getFilename().equals("a.txt"), "Sorted IDs changed");
                check(results.get(1).getId() == 3, "Empty document ID missing");
            }
        });
        run("Download header uses safe ASCII fallback and exact UTF-8 filename star", () -> {
            String name = "notes \" + % & \u09ac.txt";
            String header = (String) invoke("attachmentFilename", name);
            check(header.equals("attachment; filename=\"notes _ _ _ _ _.txt\"; filename*=UTF-8''notes%20%22%20%2B%20%25%20%26%20%E0%A6%AC.txt"), header);
            check(header.chars().allMatch(c -> c >= 32 && c <= 126), "Unsafe header characters");
        });
        run("Document query parser decodes once and preserves safe filename punctuation", () -> {
            check(invoke("parseDocumentName", "name=notes+%2B+50%25+%26+%E0%A6%AC.txt").equals("notes + 50% & \u09ac.txt"), "Wrong decoding");
            check(invoke("parseDocumentName", "name=%252e%252e%252fsecret.txt").equals("%2e%2e%2fsecret.txt"), "Double decoding");
        });
        testSymlinks();
        System.out.println("Total passed: " + passed + "; total failed: " + failed + "; total skipped: " + skipped);
        if (failed > 0) { System.exit(1); }
    }

    private static Object invoke(String name, String input) throws Exception {
        Method method = SearchServer.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        try { return method.invoke(null, input); }
        catch (InvocationTargetException error) { throw (Exception) error.getCause(); }
    }

    private static void testSymlinks() throws Exception {
        try (Fixture f = new Fixture(); Fixture outside = new Fixture()) {
            Path target = outside.write("secret.txt", utf8("secret"));
            Path link = f.folder.resolve("link.txt");
            try { Files.createSymbolicLink(link, target); }
            catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                skipped++;
                System.out.println("SKIP: Symlink rejection (creation unavailable): " + unavailable);
                return;
            }
            f.owned.add(link);
            run("External and dangling text symlinks fail closed without replacing the old index", () -> {
                f.engine.buildIndex(outside.folder.toString());
                for (int attempt = 0; attempt < 2; attempt++) {
                    try { f.build(); throw new AssertionError("Symlink accepted"); }
                    catch (IOException expected) { }
                    equalBytes(utf8("secret"), f.engine.getIndexedDocumentBytes("secret.txt"));
                    check(f.engine.getIndexedDocumentBytes("link.txt") == null, "Symlink content exposed");
                    if (attempt == 0) { Files.delete(target); }
                }
            });
        }
    }

    private static class Fixture implements AutoCloseable {
        final Path folder = Files.createTempDirectory("iit-indexed-documents-");
        final List<Path> owned = new ArrayList<>();
        final SearchEngine engine = new SearchEngine();
        Fixture() throws IOException { }
        Path write(String name, byte[] bytes) throws IOException {
            Path path = folder.resolve(name);
            if (!owned.contains(path)) { owned.add(path); }
            Files.write(path, bytes);
            return path;
        }
        void build() throws IOException { engine.buildIndex(folder.toString()); }
        public void close() throws IOException {
            for (int i = owned.size() - 1; i >= 0; i--) { Files.deleteIfExists(owned.get(i)); }
            Files.delete(folder);
        }
    }
}
