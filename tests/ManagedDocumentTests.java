import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class ManagedDocumentTests {
    private static int passed;
    private static int failed;
    private static int skipped;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private static void run(String name, Check test) {
        try { test.run(); passed++; System.out.println("PASS: " + name); }
        catch (Exception | AssertionError error) {
            failed++;
            System.out.println("FAIL: " + name + " - " + error);
            error.printStackTrace(System.out);
        }
    }

    public static void main(String[] args) throws Exception {
        run("Create an empty managed directory", ManagedDocumentTests::create);
        run("Open and index pre-existing text documents", ManagedDocumentTests::existingCollection);
        run("Add a document and publish its searchable bytes", ManagedDocumentTests::add);
        run("Multiple additions preserve documents and statistics", ManagedDocumentTests::multiple);
        run("Duplicate and case-insensitive additions preserve the original", ManagedDocumentTests::duplicates);
        run("Unicode normalization and case collisions are rejected", ManagedDocumentTests::unicodeCollisions);
        run("Replacement removes obsolete terms and preserves other documents", ManagedDocumentTests::replace);
        run("Replacement refreshes phrase and Boolean results", ManagedDocumentTests::phraseAndBoolean);
        run("Ranking and exact Top-K prefixes survive document operations", ManagedDocumentTests::ranking);
        run("Original UTF-8, BOM and line endings remain exact and defensive", ManagedDocumentTests::originalBytes);
        run("Delete removes search, positions and byte access", ManagedDocumentTests::delete);
        run("Missing replacement and deletion fail without publication", ManagedDocumentTests::missing);
        run("Replacement and deletion require exact filename case", ManagedDocumentTests::exactNames);
        run("Malformed UTF-8 and NUL input fail before disk changes", ManagedDocumentTests::invalidText);
        run("Unsafe filenames and Windows device aliases are rejected", ManagedDocumentTests::unsafeNames);
        run("Safe Unicode and punctuation filenames retain their spelling", ManagedDocumentTests::safeNames);
        run("Array and stream size limits reject oversized documents", ManagedDocumentTests::oversized);
        run("Inclusive size limit and empty text documents work", ManagedDocumentTests::sizeBoundary);
        run("Input-stream failure preserves disk and published state", ManagedDocumentTests::streamFailure);
        run("Caller-owned input streams remain open", ManagedDocumentTests::streamOwnership);
        run("Reload reconciles offline additions, replacement and deletion", ManagedDocumentTests::reload);
        run("Failed reload preserves the previous usable generation", ManagedDocumentTests::failedReload);
        run("Completed operations persist across close and reopen", ManagedDocumentTests::persistence);
        run("Partial staging leftovers are ignored during reopening", ManagedDocumentTests::stagingRecovery);
        run("Interrupted add is reconciled from visible disk files", () -> interrupted("add"));
        run("Interrupted replacement is reconciled from visible disk files", () -> interrupted("replace"));
        run("Interrupted deletion is reconciled from visible disk files", () -> interrupted("delete"));
        run("Failed add rebuild rolls back the disk and retains the index", () -> rollback("add"));
        run("Failed replacement rebuild restores original bytes", () -> rollback("replace"));
        run("Failed deletion rebuild restores the deleted document", () -> rollback("delete"));
        run("Rollback failure is explicit and blocks mutations until reload", ManagedDocumentTests::rollbackFailure);
        run("Concurrent readers finish while a document rebuild is paused", ManagedDocumentTests::concurrentReaders);
        run("Concurrent additions serialize without losing either document", ManagedDocumentTests::concurrentMutations);
        run("Concurrent duplicate additions cannot overwrite the winner", ManagedDocumentTests::concurrentDuplicate);
        run("A second open store cannot acquire the collection lock", ManagedDocumentTests::exclusiveOpen);
        run("Separate managed roots and engines remain isolated", ManagedDocumentTests::isolation);
        run("Unsafe collection and staging entries fail closed", ManagedDocumentTests::unsafeEntries);
        run("Offline changes require explicit reload before mutation", ManagedDocumentTests::externalChanges);
        run("Closed stores reject changes and release their lock", ManagedDocumentTests::closedStore);
        run("Reopening with a smaller limit rejects oversized stored input", ManagedDocumentTests::reopenLimit);
        symlinkFixture();
        System.out.println("Managed documents: total passed: " + passed + "; total failed: " + failed + "; total skipped: " + skipped);
        if (failed > 0) { System.exit(1); }
    }

    private static void create() throws Exception {
        try (Fixture f = new Fixture()) {
            check(Files.isDirectory(f.root), "Directory not created");
            check(f.engine().getDocumentCount() == 0 && f.engine().search("alpha").isEmpty(), "Initial index");
            check(f.engine().getIndexedDocumentBytes("missing.txt") == null, "Unknown bytes");
        }
    }

    private static void existingCollection() throws Exception {
        try (Fixture f = new Fixture(false, 128)) {
            Files.createDirectory(f.root);
            Files.write(f.root.resolve("old.txt"), utf8("legacy alpha"));
            f.store = new DocumentStore(f.root, 128);
            check(names(f.engine().searchBoolean("legacy")).equals(List.of("old.txt")), "Existing content not loaded");
        }
    }

    private static void add() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.add("a.txt", utf8("alpha beta"));
            check(names(f.engine().searchBoolean("alpha")).equals(List.of("a.txt")), "Added result");
            equal(utf8("alpha beta"), Files.readAllBytes(f.root.resolve("a.txt")));
            equal(utf8("alpha beta"), f.engine().getIndexedDocumentBytes("a.txt"));
        }
    }

    private static void multiple() throws Exception {
        try (Fixture f = populated()) {
            f.store.add("c.txt", utf8("delta alpha"));
            List<String> inventory = f.store.listDocumentNames();
            check(inventory.equals(List.of("a.txt", "b.txt", "c.txt")), "Sorted published inventory");
            expect(UnsupportedOperationException.class, () -> inventory.add("other.txt"));
            check(f.engine().getDocumentCount() == 3 && f.engine().getUniqueTermCount() == 4
                    && f.engine().getTotalTokens() == 7, "Collection statistics");
            check(names(f.engine().searchBoolean("alpha")).equals(List.of("a.txt", "b.txt", "c.txt")), "Earlier documents lost");
        }
    }

    private static void duplicates() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            for (String name : List.of("a.txt", "A.txt", "A.TXT")) {
                expect(FileAlreadyExistsException.class, () -> f.store.add(name, utf8("fresh")));
            }
            check(fingerprint(f.engine()).equals(before), "Duplicate changed index");
            equal(utf8("alpha beta"), Files.readAllBytes(f.root.resolve("a.txt")));
        }
    }

    private static void unicodeCollisions() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.add("caf\u00e9.txt", utf8("alpha"));
            expect(FileAlreadyExistsException.class, () -> f.store.add("CAFE\u0301.TXT", utf8("beta")));
            f.store.add("\u03c3.txt", utf8("gamma"));
            expect(FileAlreadyExistsException.class, () -> f.store.add("\u03c2.txt", utf8("beta")));
            check(f.engine().getDocumentCount() == 2, "Unicode collision accepted");
        }
    }

    private static void replace() throws Exception {
        try (Fixture f = populated()) {
            f.store.replace("a.txt", utf8("fresh fresh"));
            check(f.engine().search("beta").isEmpty(), "Obsolete term retained");
            check(names(f.engine().searchBoolean("fresh")).equals(List.of("a.txt")), "Replacement missing");
            equal(utf8("fresh fresh"), f.engine().getIndexedDocumentBytes("a.txt"));
            equal(utf8("gamma alpha gamma"), Files.readAllBytes(f.root.resolve("b.txt")));
        }
    }

    private static void phraseAndBoolean() throws Exception {
        try (Fixture f = populated()) {
            check(names(f.engine().searchPhrase("alpha beta")).equals(List.of("a.txt")), "Old phrase");
            f.store.replace("a.txt", utf8("fresh and beta"));
            check(f.engine().searchPhrase("alpha beta").isEmpty(), "Obsolete phrase retained");
            check(names(f.engine().searchPhrase("fresh and beta")).equals(List.of("a.txt")), "Stop-word phrase");
            check(names(f.engine().searchBoolean("fresh AND beta OR gamma")).equals(List.of("a.txt", "b.txt")), "Boolean groups");
            check(f.engine().searchBoolean("alpha AND beta").isEmpty(), "Obsolete Boolean match");
        }
    }

    private static void ranking() throws Exception {
        try (Fixture f = populated()) {
            f.store.add("c.txt", utf8("alpha alpha alpha"));
            double idf = Math.log(4.0 / 4.0) + 1;
            check(f.engine().search("alpha").get(0).getScore() == 3 * idf, "Independent raw TF-IDF expectation");
            for (int round = 0; round < 3; round++) {
                SearchEngine oracle = new SearchEngine();
                oracle.buildIndex(f.root.toString());
                for (String query : List.of("alpha", "gamma alpha", "alpha alpha beta", "unknown", "the and")) {
                    var full = f.engine().search(query);
                    check(ranked(full).equals(ranked(oracle.search(query))), "Full rebuild oracle");
                    for (int k : new int[] {1, 2, 5, Integer.MAX_VALUE}) {
                        check(ranked(f.engine().searchTopK(query, k)).equals(ranked(full.subList(0, Math.min(k, full.size())))), "Exact prefix/score bits");
                    }
                }
                if (round == 0) { f.store.replace("a.txt", utf8("gamma gamma gamma beta")); }
                if (round == 1) { f.store.delete("b.txt"); }
            }
        }
    }

    private static void originalBytes() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] input = utf8("\ufeffalpha\r\n\u09ac\u09be\u0982\u09b2\u09be & <text>\n");
            byte[] expected = input.clone();
            f.store.add("a.txt", input);
            input[0] ^= 1;
            equal(expected, Files.readAllBytes(f.root.resolve("a.txt")));
            byte[] returned = f.engine().getIndexedDocumentBytes("a.txt");
            equal(expected, returned);
            returned[0] ^= 1;
            equal(expected, f.engine().getIndexedDocumentBytes("a.txt"));
            f.store.replace("a.txt", utf8("fresh\r\n"));
            equal(utf8("fresh\r\n"), f.engine().getIndexedDocumentBytes("a.txt"));
        }
    }

    private static void delete() throws Exception {
        try (Fixture f = populated()) {
            var retained = f.engine().getPositionalPostings("beta");
            f.store.delete("a.txt");
            check(!Files.exists(f.root.resolve("a.txt")), "Deleted file remains visible");
            check(f.engine().getIndexedDocumentBytes("a.txt") == null && f.engine().getPositionalPostings("beta").isEmpty(), "Deleted snapshots retained");
            check(f.engine().search("beta").isEmpty() && f.engine().searchPhrase("alpha beta").isEmpty()
                    && f.engine().searchBoolean("beta").isEmpty(), "Deleted result retained");
            check(retained.equals(Map.of(1, List.of(1))), "Previous positional snapshot mutated");
            check(f.engine().searchTopK("alpha", 1).get(0).getDocument().getId() == 1, "Contiguous rebuild IDs");
            f.store.delete("b.txt");
            check(f.engine().getDocumentCount() == 0 && f.engine().getTotalTokens() == 0, "Delete last document");
        }
    }

    private static void missing() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            expect(NoSuchFileException.class, () -> f.store.delete("missing.txt"));
            expect(NoSuchFileException.class, () -> f.store.replace("missing.txt", utf8("fresh")));
            check(fingerprint(f.engine()).equals(before), "Missing operation changed state");
        }
    }

    private static void exactNames() throws Exception {
        try (Fixture f = populated()) {
            expect(NoSuchFileException.class, () -> f.store.replace("A.txt", utf8("fresh")));
            expect(NoSuchFileException.class, () -> f.store.delete("A.txt"));
            equal(utf8("alpha beta"), f.engine().getIndexedDocumentBytes("a.txt"));
        }
    }

    private static void invalidText() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            for (byte[] invalid : List.of(new byte[] {(byte) 0xc3, 0x28}, new byte[] {(byte) 0xff}, utf8("alpha\u0000beta"))) {
                expect(IllegalArgumentException.class, () -> f.store.add("new.txt", invalid));
                expect(IllegalArgumentException.class, () -> f.store.replace("a.txt", invalid));
            }
            check(fingerprint(f.engine()).equals(before) && !Files.exists(f.root.resolve("new.txt")), "Invalid text published");
        }
    }

    private static void unsafeNames() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            List<String> names = new ArrayList<>(Arrays.asList(null, "", ".", "..", ".txt", "../a.txt", "..\\a.txt",
                    "/a.txt", "C:\\a.txt", "C:a.txt", "a/b.txt", "a\\b.txt", "a.txt:stream", "a:stream.txt",
                    "a?.txt", "a*.txt", "a|b.txt", "<a>.txt", "a\".txt", "a\n.txt", "a\u0000.txt",
                    "a\u007f.txt", "a.txt ", "a.txt.", " a.txt", "a.pdf", "a.txt.exe", "\ud800.txt"));
            for (String device : List.of("CON", "prn", "AUX", "NUL", "COM1", "COM9", "LPT1", "LPT9", "COM\u00b9", "LPT\u00b2", "CONIN$", "CONOUT$", "CLOCK$", "CON ", "NUL.extra")) {
                names.add(device + ".txt");
            }
            names.add("x".repeat(252) + ".txt");
            for (String name : names) {
                expect(IllegalArgumentException.class, () -> f.store.add(name, utf8("fresh")));
                expect(IllegalArgumentException.class, () -> f.store.replace(name, utf8("fresh")));
                expect(IllegalArgumentException.class, () -> f.store.delete(name));
            }
            check(fingerprint(f.engine()).equals(before), "Unsafe name changed state");
        }
    }

    private static void safeNames() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String name : List.of("notes + 50% & \u09ac\u09be\u0982\u09b2\u09be.TXT", "COM10.txt", "notes..txt")) {
                f.store.add(name, utf8("alpha"));
                equal(utf8("alpha"), f.engine().getIndexedDocumentBytes(name));
                check(Files.exists(f.root.resolve(name)), "Filename changed");
            }
        }
    }

    private static void oversized() throws Exception {
        try (Fixture f = new Fixture(true, 16)) {
            check(f.store.getMaxDocumentBytes() == 16, "Configured limit access");
            f.store.add("a.txt", utf8("alpha"));
            var before = fingerprint(f.engine());
            expect(IllegalArgumentException.class, () -> f.store.add("new.txt", new byte[17]));
            expect(IllegalArgumentException.class, () -> f.store.replace("a.txt", new byte[17]));
            int[] consumed = {0};
            InputStream infinite = new InputStream() { public int read() { consumed[0]++; return 'a'; } };
            expect(IllegalArgumentException.class, () -> f.store.add("new.txt", infinite));
            check(consumed[0] == 17, "Stream exceeded limit+1 read budget");
            check(fingerprint(f.engine()).equals(before), "Oversize input changed state");
        }
    }

    private static void sizeBoundary() throws Exception {
        try (Fixture f = new Fixture(true, 16)) {
            f.store.add("a.txt", utf8("a".repeat(16)));
            f.store.add("empty.txt", new byte[0]);
            check(f.engine().getDocumentCount() == 2, "Boundary documents rejected");
            equal(new byte[0], f.engine().getIndexedDocumentBytes("empty.txt"));
            f.store.replace("a.txt", utf8("b".repeat(16)));
        }
    }

    private static void streamFailure() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            InputStream failing = new InputStream() {
                private int count;
                public int read() throws IOException {
                    if (count++ >= 4) { throw new IOException("Injected input failure"); }
                    return 'a';
                }
            };
            expect(IOException.class, () -> f.store.replace("a.txt", failing));
            check(fingerprint(f.engine()).equals(before), "Input failure changed state");
            equal(utf8("alpha beta"), Files.readAllBytes(f.root.resolve("a.txt")));
        }
    }

    private static void streamOwnership() throws Exception {
        try (Fixture f = new Fixture()) {
            boolean[] closed = {false};
            InputStream stream = new ByteArrayInputStream(utf8("alpha")) {
                public void close() { closed[0] = true; }
            };
            f.store.add("a.txt", stream);
            check(!closed[0], "Caller stream closed");
        }
    }

    private static void reload() throws Exception {
        try (Fixture f = populated()) {
            Files.write(f.root.resolve("a.txt"), utf8("fresh"));
            Files.delete(f.root.resolve("b.txt"));
            Files.write(f.root.resolve("c.txt"), utf8("delta"));
            check(!f.engine().search("beta").isEmpty(), "Offline edit changed old snapshot");
            f.store.reload();
            check(f.engine().search("beta").isEmpty() && f.engine().getIndexedDocumentBytes("b.txt") == null, "Reload retained old state");
            check(names(f.engine().searchBoolean("fresh OR delta")).equals(List.of("a.txt", "c.txt")), "Offline files not reconciled");
        }
    }

    private static void failedReload() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            Files.write(f.root.resolve("a.txt"), new byte[] {(byte) 0xff});
            expect(IOException.class, f.store::reload);
            check(fingerprint(f.engine()).equals(before), "Failed reload published partial state");
            Files.write(f.root.resolve("a.txt"), utf8("fresh"));
            f.store.reload();
            check(!f.engine().search("fresh").isEmpty(), "Reload did not recover");
        }
    }

    private static void persistence() throws Exception {
        try (Fixture f = populated()) {
            f.store.replace("a.txt", utf8("fresh beta"));
            f.store.add("c.txt", utf8("delta"));
            f.store.delete("b.txt");
            var before = fingerprint(f.engine());
            f.reopen();
            check(fingerprint(f.engine()).equals(before), "Reopened state differs");
            check(f.engine().getIndexedDocumentBytes("b.txt") == null, "Deletion lost on restart");
        }
    }

    private static void stagingRecovery() throws Exception {
        try (Fixture f = populated()) {
            f.store.close();
            Files.write(f.root.resolve(".iit-store/next"), new byte[] {(byte) 0xff});
            Files.write(f.root.resolve(".iit-store/previous"), utf8("unacknowledged old backup"));
            f.store = new DocumentStore(f.root);
            check(f.engine().getDocumentCount() == 2 && f.engine().search("unacknowledged").isEmpty(), "Staging file indexed/replayed");
            f.store.add("new.txt", utf8("fresh"));
            check(!Files.exists(f.root.resolve(".iit-store/previous")), "Old backup not reclaimed before mutation");
        }
    }

    private static final class SimulatedInterruption extends Error { }

    private static void interrupted(String operation) throws Exception {
        try (Fixture f = populated()) {
            setProcessor(f.engine(), new TextProcessor() {
                public List<String> tokenize(String text) { throw new SimulatedInterruption(); }
            });
            try { perform(f.store, operation); throw new AssertionError("Interruption did not occur"); }
            catch (SimulatedInterruption expected) { }
            f.reopen();
            if (operation.equals("delete")) {
                check(f.engine().getIndexedDocumentBytes("a.txt") == null, "Interrupted deletion reversed on reopen");
            } else {
                String name = operation.equals("add") ? "new.txt" : "a.txt";
                equal(utf8("fresh beta"), f.engine().getIndexedDocumentBytes(name));
            }
            check(f.engine().getDocumentCount() == (operation.equals("add") ? 3 : operation.equals("delete") ? 1 : 2), "Interrupted operation count");
        }
    }

    private static void rollback(String operation) throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            setProcessor(f.engine(), new TextProcessor() {
                public List<String> tokenize(String text) { throw new IllegalStateException("Injected rebuild failure"); }
            });
            expect(IOException.class, () -> perform(f.store, operation));
            setProcessor(f.engine(), new TextProcessor());
            check(fingerprint(f.engine()).equals(before), "Failure changed published generation");
            equal(utf8("alpha beta"), Files.readAllBytes(f.root.resolve("a.txt")));
            check(!Files.exists(f.root.resolve("new.txt")), "Failed addition retained");
            f.store.add("new.txt", utf8("fresh"));
            check(!f.engine().search("fresh").isEmpty(), "Store unusable after rollback");
        }
    }

    private static void perform(DocumentStore store, String operation) throws IOException {
        switch (operation) {
            case "add" -> store.add("new.txt", utf8("fresh beta"));
            case "replace" -> store.replace("a.txt", utf8("fresh beta"));
            case "delete" -> store.delete("a.txt");
            default -> throw new AssertionError("Unknown fixture operation");
        }
    }

    private static void rollbackFailure() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            setProcessor(f.engine(), new TextProcessor() {
                public List<String> tokenize(String text) {
                    try { Files.write(f.root.resolve(".iit-store/previous"), utf8("damaged backup")); }
                    catch (IOException error) { throw new IllegalStateException(error); }
                    throw new IllegalStateException("Injected failure with damaged backup");
                }
            });
            Exception failure = expect(IOException.class, () -> f.store.replace("a.txt", utf8("fresh beta")));
            check(failure.getMessage().contains("rollback failed") && failure.getSuppressed().length == 1, "Rollback failure hidden");
            setProcessor(f.engine(), new TextProcessor());
            check(fingerprint(f.engine()).equals(before), "Failed rollback changed published index");
            expect(IOException.class, () -> f.store.add("new.txt", utf8("delta")));
            f.store.reload();
            equal(utf8("fresh beta"), f.engine().getIndexedDocumentBytes("a.txt"));
            f.store.add("new.txt", utf8("delta"));
        }
    }

    private static void concurrentReaders() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            Gate gate = pauseRebuild(f.engine());
            try (Task<Void> writer = new Task<>(() -> { f.store.replace("a.txt", utf8("holdmarker fresh")); return null; })) {
                try {
                    gate.entered();
                    try (Task<List<Object>> reader = new Task<>(() -> fingerprint(f.engine()))) {
                        check(reader.result().equals(before), "Reader blocked or saw partial state");
                    }
                } finally { gate.release(); }
                writer.result();
            }
            check(f.engine().search("beta").isEmpty(), "Replacement not published");
            equal(utf8("holdmarker fresh"), f.engine().getIndexedDocumentBytes("a.txt"));
        }
    }

    private static void concurrentMutations() throws Exception {
        concurrentAdds(false);
    }

    private static void concurrentDuplicate() throws Exception {
        concurrentAdds(true);
    }

    private static void concurrentAdds(boolean duplicate) throws Exception {
        try (Fixture f = populated()) {
            Gate gate = pauseRebuild(f.engine());
            try (Task<Void> first = new Task<>(() -> { f.store.add("new.txt", utf8("holdmarker fresh")); return null; })) {
                try {
                    gate.entered();
                    try (Task<Void> second = new Task<>(() -> {
                        if (duplicate) { expect(FileAlreadyExistsException.class, () -> f.store.add("new.txt", utf8("delta"))); }
                        else { f.store.add("second.txt", utf8("delta")); }
                        return null;
                    })) {
                        try { awaitBlocked(second.thread, first.thread); }
                        finally { gate.release(); }
                        first.result();
                        second.result();
                    }
                } finally { gate.release(); }
            }
            equal(utf8("holdmarker fresh"), f.engine().getIndexedDocumentBytes("new.txt"));
            check(f.engine().getDocumentCount() == (duplicate ? 3 : 4), "Concurrent mutation lost data");
            if (!duplicate) { equal(utf8("delta"), f.engine().getIndexedDocumentBytes("second.txt")); }
        }
    }

    private static void exclusiveOpen() throws Exception {
        try (Fixture f = populated()) {
            expect(IOException.class, () -> {
                try (DocumentStore unexpected = new DocumentStore(f.root)) { throw new AssertionError("Duplicate store opened"); }
            });
            f.reopen();
            check(f.engine().getDocumentCount() == 2, "Lock not released for reopening");
        }
    }

    private static void isolation() throws Exception {
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            first.store.add("same.txt", utf8("alpha"));
            second.store.add("same.txt", utf8("beta"));
            check(first.engine().search("beta").isEmpty() && second.engine().search("alpha").isEmpty(), "Root isolation");
        }
    }

    private static void unsafeEntries() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            Path nested = Files.createDirectory(f.root.resolve("nested.txt"));
            expect(IOException.class, f.store::reload);
            Files.delete(nested);
            Files.write(f.root.resolve("private.md"), utf8("secret"));
            expect(IOException.class, f.store::reload);
            Files.delete(f.root.resolve("private.md"));
            Path unexpected = f.root.resolve(".iit-store/unknown");
            Files.write(unexpected, utf8("secret"));
            expect(IOException.class, () -> f.store.add("new.txt", utf8("fresh")));
            Files.delete(unexpected);
            check(fingerprint(f.engine()).equals(before), "Unsafe entry changed published state");
        }
        try (Fixture f = new Fixture(false, 128)) {
            Files.createDirectory(f.root);
            Files.write(f.root.resolve("private.md"), utf8("secret"));
            expect(IOException.class, () -> { try (DocumentStore unused = new DocumentStore(f.root)) { throw new AssertionError("Unsafe collection opened"); } });
            check(!Files.exists(f.root.resolve(".iit-store")), "Rejected collection changed");
        }
    }

    private static void externalChanges() throws Exception {
        try (Fixture f = populated()) {
            var before = fingerprint(f.engine());
            Files.write(f.root.resolve("a.txt"), utf8("offline"));
            expect(IOException.class, () -> f.store.add("new.txt", utf8("fresh")));
            check(fingerprint(f.engine()).equals(before), "External changes published implicitly");
            f.store.reload();
            Files.delete(f.root.resolve("b.txt"));
            expect(IOException.class, () -> f.store.replace("a.txt", utf8("fresh")));
            f.store.reload();
            f.store.replace("a.txt", utf8("fresh"));
        }
    }

    private static void closedStore() throws Exception {
        try (Fixture f = populated()) {
            f.store.close();
            f.store.close();
            expect(IOException.class, () -> f.store.add("new.txt", utf8("fresh")));
            expect(IOException.class, () -> f.store.replace("a.txt", utf8("fresh")));
            expect(IOException.class, () -> f.store.delete("a.txt"));
            expect(IOException.class, f.store::reload);
            expect(IOException.class, f.store::listDocumentNames);
            f.store = new DocumentStore(f.root);
            check(f.engine().getDocumentCount() == 2, "Closed collection lost data");
        }
    }

    private static void reopenLimit() throws Exception {
        try (Fixture f = populated()) {
            f.store.close();
            expect(IOException.class, () -> { try (DocumentStore unused = new DocumentStore(f.root, 4)) { throw new AssertionError("Oversized collection opened"); } });
            equal(utf8("alpha beta"), Files.readAllBytes(f.root.resolve("a.txt")));
            f.store = new DocumentStore(f.root);
        }
    }

    private static void symlinkFixture() throws Exception {
        try (Fixture f = populated()) {
            Path outside = f.parent.resolve("outside.txt");
            Files.write(outside, utf8("secret"));
            Path link = f.root.resolve("link.txt");
            try { Files.createSymbolicLink(link, outside); }
            catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                skipped++;
                System.out.println("SKIP: Managed symlink rejection (creation unavailable): " + unavailable);
                return;
            }
            run("Linked documents and collection roots fail closed", () -> {
                var before = fingerprint(f.engine());
                expect(IOException.class, f.store::reload);
                expect(IOException.class, () -> f.store.add("new.txt", utf8("fresh")));
                check(fingerprint(f.engine()).equals(before), "Link changed published state");
                equal(utf8("secret"), Files.readAllBytes(outside));
                Files.delete(link);
                f.store.close();
                Path rootLink = f.parent.resolve("linked-root");
                Files.createSymbolicLink(rootLink, f.root);
                expect(IOException.class, () -> { try (DocumentStore unused = new DocumentStore(rootLink)) { throw new AssertionError("Linked root opened"); } });
            });
        }
    }

    private static Gate pauseRebuild(SearchEngine engine) throws Exception {
        Gate gate = new Gate();
        setProcessor(engine, new TextProcessor() {
            public List<String> tokenize(String text) {
                if (text.contains("holdmarker")) { gate.pause(); }
                return super.tokenize(text);
            }
        });
        return gate;
    }

    private static void setProcessor(SearchEngine engine, TextProcessor processor) throws Exception {
        Field field = SearchEngine.class.getDeclaredField("processor");
        field.setAccessible(true);
        field.set(engine, processor);
    }

    private static List<Object> fingerprint(SearchEngine engine) {
        return List.of(ranked(engine.search("alpha beta gamma legacy fresh java")),
                ranked(engine.searchTopK("alpha beta gamma", 2)), names(engine.searchPhrase("alpha beta")),
                names(engine.searchBoolean("alpha AND beta OR gamma OR fresh")), engine.getDocumentCount(),
                engine.getUniqueTermCount(), engine.getTotalTokens(), engine.getPositionalPostings("alpha"),
                engine.getPositionalPostings("beta"), Arrays.toString(engine.getIndexedDocumentBytes("a.txt")),
                Arrays.toString(engine.getIndexedDocumentBytes("b.txt")), Arrays.toString(engine.getIndexedDocumentBytes("new.txt")));
    }

    private static List<String> ranked(List<SearchResult> results) {
        return results.stream().map(r -> r.getDocument().getId() + ":" + r.getDocument().getFilename()
                + ":" + Double.doubleToLongBits(r.getScore())).toList();
    }

    private static List<String> names(List<Document> documents) { return documents.stream().map(Document::getFilename).toList(); }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void equal(byte[] expected, byte[] actual) { check(Arrays.equals(expected, actual), "Bytes differ"); }

    private static Exception expect(Class<? extends Exception> type, Check operation) throws Exception {
        try { operation.run(); }
        catch (Exception error) { check(type.isInstance(error), "Expected " + type.getSimpleName() + ", got " + error); return error; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void await(CountDownLatch latch) {
        try { check(latch.await(15, TimeUnit.SECONDS), "Thread gate timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static void awaitBlocked(Thread waiting, Thread owner) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiting.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockOwnerId() == owner.threadId()) { return; }
            check(waiting.isAlive(), "Second mutation did not wait");
            Thread.yield();
        }
        throw new AssertionError("Second mutation was not serialized");
    }

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        void pause() { entered.countDown(); await(released); }
        void entered() { await(entered); }
        void release() { released.countDown(); }
    }

    private static final class Task<T> implements AutoCloseable {
        private final FutureTask<T> future;
        private final Thread thread;
        Task(Callable<T> work) {
            future = new FutureTask<>(work);
            thread = new Thread(future, "managed-document-test");
            thread.setDaemon(true);
            thread.start();
        }
        T result() throws Exception { return future.get(15, TimeUnit.SECONDS); }
        public void close() throws Exception {
            thread.join(15000);
            if (thread.isAlive()) { thread.interrupt(); thread.join(1000); }
            check(!thread.isAlive(), "Test thread did not terminate");
        }
    }

    private static Fixture populated() throws Exception {
        Fixture f = new Fixture();
        try { f.store.add("a.txt", utf8("alpha beta")); f.store.add("b.txt", utf8("gamma alpha gamma")); return f; }
        catch (Exception | AssertionError failure) {
            try { f.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path parent = Files.createTempDirectory("iit-managed-");
        private final Path root = parent.resolve("collection");
        private final int limit;
        private DocumentStore store;
        Fixture() throws IOException { this(true, DocumentStore.DEFAULT_MAX_DOCUMENT_BYTES); }
        Fixture(boolean open, int limit) throws IOException {
            this.limit = limit;
            if (open) { store = new DocumentStore(root, limit); }
        }
        SearchEngine engine() { return store.getSearchEngine(); }
        void reopen() throws IOException { store.close(); store = new DocumentStore(root, limit); }
        public void close() throws Exception {
            try { if (store != null) { store.close(); } }
            finally {
                // This tree was created exclusively by this fixture. Never follow links.
                Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
                check(parent.toAbsolutePath().normalize().startsWith(tempRoot)
                        && parent.getFileName().toString().startsWith("iit-managed-"), "Unsafe cleanup target");
                try (var entries = Files.walk(parent)) {
                    for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
                }
            }
        }
    }
}
