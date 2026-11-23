import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One exclusively owned managed collection. Keep its directory private to this
 * store while open; the file lock coordinates cooperating stores, not arbitrary
 * filesystem writers. Use reload() to reconcile intentional offline changes.
 *
 * Visible .txt files are the restart source of truth. Atomic moves require a
 * supporting filesystem. This is not a crash-atomic disk-and-memory transaction:
 * an interrupted, unacknowledged change may survive and be loaded on reopening.
 * Staging and backup files remain private until the next mutation reclaims them.
 * Invalid names/content raise IllegalArgumentException; storage, existence and
 * recovery failures raise IOException. No operation accepts a caller-supplied path.
 */
public final class DocumentStore implements AutoCloseable {
    /** Default per-document limit, also checked when opening or reloading files. */
    public static final int DEFAULT_MAX_DOCUMENT_BYTES = 1024 * 1024;
    private static final String WORK_DIRECTORY = ".iit-store";
    private static final Set<String> WORK_FILES = Set.of("lock", "next", "previous");
    private final Path root;
    private final Path work;
    private final int maxDocumentBytes;
    private final SearchEngine engine = new SearchEngine();
    private final Object rootKey;
    private final Object workKey;
    private FileChannel lockChannel;
    private FileLock collectionLock;
    private Object lockKey;
    private Set<String> indexedNames = Set.of();
    private boolean closed;
    private boolean recoveryRequired;

    public DocumentStore(Path directory) throws IOException {
        this(directory, DEFAULT_MAX_DOCUMENT_BYTES);
    }

    /** Creates the directory if absent; its parent must already exist. */
    public DocumentStore(Path directory, int maxDocumentBytes) throws IOException {
        if (directory == null || maxDocumentBytes < 1) {
            throw new IllegalArgumentException("A directory and positive document size limit are required.");
        }
        this.maxDocumentBytes = maxDocumentBytes;
        root = directory.toAbsolutePath().normalize();
        checkDirectories(root.getParent());
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(root); }
        checkDirectories(root);
        rootKey = attributes(root).fileKey();
        work = root.resolve(WORK_DIRECTORY);
        // Reject an unsuitable collection before creating private bookkeeping.
        scan(false);
        if (!Files.exists(work, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(work); }
        checkDirectories(work);
        workKey = attributes(work).fileKey();
        try {
            checkWorkFiles();
            lockChannel = FileChannel.open(work.resolve("lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { collectionLock = lockChannel.tryLock(); }
            catch (OverlappingFileLockException busy) {
                throw new IOException("Managed collection is already open.", busy);
            }
            if (collectionLock == null) { throw new IOException("Managed collection is already open."); }
            lockKey = attributes(work.resolve("lock")).fileKey();
            reload();
        } catch (IOException | RuntimeException failure) {
            try { if (lockChannel != null) { lockChannel.close(); } }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Retrieval access only. Rebuild this owned engine through reload(). */
    public SearchEngine getSearchEngine() { return engine; }

    /** Published inventory only; offline changes require reload(). */
    public synchronized List<String> listDocumentNames() throws IOException {
        ensureUsable();
        return indexedNames.stream().sorted().toList();
    }

    public int getMaxDocumentBytes() { return maxDocumentBytes; }

    public synchronized void add(String filename, byte[] content) throws IOException {
        add(filename, byteInput(content));
    }

    /** The caller owns the stream; at most limit+1 bytes are consumed. */
    public synchronized void add(String filename, InputStream content) throws IOException {
        ensureUsable();
        validateFilename(filename);
        change(filename, readInput(content), Operation.ADD);
    }

    public synchronized void replace(String filename, byte[] content) throws IOException {
        replace(filename, byteInput(content));
    }

    /** The caller owns the stream; at most limit+1 bytes are consumed. */
    public synchronized void replace(String filename, InputStream content) throws IOException {
        ensureUsable();
        validateFilename(filename);
        change(filename, readInput(content), Operation.REPLACE);
    }

    public synchronized void delete(String filename) throws IOException {
        ensureUsable();
        validateFilename(filename);
        change(filename, null, Operation.DELETE);
    }

    /** Validates the whole collection before rebuilding; failed builds keep the old snapshot. */
    public synchronized void reload() throws IOException {
        ensureOpen();
        synchronized (engine) {
            checkLocations();
            Set<String> names = scan(false);
            engine.buildIndex(root.toString());
            indexedNames = names;
            recoveryRequired = false;
        }
    }

    private enum Operation { ADD, REPLACE, DELETE }

    private void change(String filename, byte[] content, Operation operation) throws IOException {
        // Match buildIndex's monitor so even a direct rebuild cannot publish the
        // temporary disk state during rollback. Retrieval takes neither lock.
        synchronized (engine) {
            checkLocations();
            scan(true);
            boolean exists = indexedNames.contains(filename);
            if (operation == Operation.ADD) {
                for (String name : indexedNames) {
                    if (collides(name, filename)) { throw new FileAlreadyExistsException(filename); }
                }
            } else if (!exists) { throw new NoSuchFileException(filename); }

            Set<String> names = new HashSet<>(indexedNames);
            if (operation == Operation.DELETE) { names.remove(filename); }
            else { names.add(filename); }
            names = Set.copyOf(names);
            Path target = root.resolve(filename);
            Path next = work.resolve("next");
            Path previous = work.resolve("previous");
            byte[] oldBytes = exists ? engine.getIndexedDocumentBytes(filename) : null;
            // Leftovers are never replayed. Retaining at most these two files
            // avoids a fallible cleanup step after successful index publication.
            clearWorkFiles();
            if (content != null) { stage(next, content); }
            if (operation == Operation.REPLACE) { stage(previous, oldBytes); }
            checkLocations();
            if (operation == Operation.ADD) { requireBytes(target, null); }
            else { requireBytes(target, oldBytes); }

            try {
                if (operation == Operation.DELETE) { atomicMove(target, previous); }
                else { atomicMove(next, target); }
                checkLocations();
                engine.buildIndex(root.toString());
            } catch (IOException | RuntimeException failure) {
                try {
                    checkLocations();
                    // A failed atomic move may have left the original in place.
                    // Inspect before restoring; never overwrite unexpected content.
                    if (!hasBytes(target, oldBytes)) {
                        requireBytes(target, content);
                        if (operation == Operation.ADD) { Files.delete(target); }
                        else {
                            requireBytes(previous, oldBytes);
                            atomicMove(previous, target);
                        }
                    }
                    scan(true);
                } catch (IOException | RuntimeException rollback) {
                    recoveryRequired = true;
                    IOException problem = new IOException(
                            "Document update failed and rollback failed; repair the collection and call reload().", failure);
                    problem.addSuppressed(rollback);
                    throw problem;
                }
                throw new IOException("Document update failed; previous disk content and index were preserved.", failure);
            }
            indexedNames = names;
        }
    }

    private Set<String> scan(boolean comparePublished) throws IOException {
        List<String> names = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (name.equals(WORK_DIRECTORY)) {
                    checkDirectories(path);
                    continue;
                }
                try { validateFilename(name); }
                catch (IllegalArgumentException invalid) { throw new IOException("Managed collection contains an invalid filename.", invalid); }
                requireRegularFile(path);
                for (String seen : names) {
                    if (collides(seen, name)) { throw new IOException("Managed collection contains colliding filenames."); }
                }
                byte[] bytes = readFile(path);
                try { validateText(bytes); }
                catch (IllegalArgumentException invalid) { throw new IOException("Managed collection contains an invalid document.", invalid); }
                if (comparePublished && (!indexedNames.contains(name)
                        || !Arrays.equals(bytes, engine.getIndexedDocumentBytes(name)))) {
                    throw new IOException("Collection changed outside DocumentStore; call reload().");
                }
                names.add(name);
            }
        }
        Set<String> result = Set.copyOf(names);
        if (comparePublished && !result.equals(indexedNames)) {
            throw new IOException("Collection changed outside DocumentStore; call reload().");
        }
        return result;
    }

    private InputStream byteInput(byte[] bytes) {
        if (bytes == null || bytes.length > maxDocumentBytes) {
            throw new IllegalArgumentException("Document is missing or exceeds the byte limit.");
        }
        return new ByteArrayInputStream(bytes);
    }

    private byte[] readInput(InputStream input) throws IOException {
        if (input == null) { throw new IllegalArgumentException("Document content is required."); }
        byte[] bytes = readLimited(input);
        validateText(bytes);
        return bytes;
    }

    private byte[] readFile(Path path) throws IOException {
        requireRegularFile(path);
        if (Files.size(path) > maxDocumentBytes) { throw new IOException("Stored document exceeds the byte limit."); }
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return readLimited(java.nio.channels.Channels.newInputStream(channel));
        } catch (IllegalArgumentException oversized) {
            throw new IOException("Stored document exceeds the byte limit.", oversized);
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxDocumentBytes, 8192));
        byte[] buffer = new byte[8192];
        while (true) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, (long) maxDocumentBytes - output.size() + 1));
            if (count < 0) { return output.toByteArray(); }
            if (count == 0) {
                int value = input.read();
                if (value < 0) { return output.toByteArray(); }
                buffer[0] = (byte) value;
                count = 1;
            }
            if ((long) output.size() + count > maxDocumentBytes) {
                throw new IllegalArgumentException("Document exceeds the byte limit.");
            }
            output.write(buffer, 0, count);
        }
    }

    private static void validateText(byte[] bytes) {
        try {
            // NUL is valid UTF-8, but is not accepted in these plain-text documents.
            if (StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).chars().anyMatch(c -> c == 0)) {
                throw new IllegalArgumentException("Text documents must not contain NUL.");
            }
        }
        catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Document must contain valid UTF-8.", invalid); }
    }

    private static void validateFilename(String name) {
        if (name == null || name.length() > 255 || !name.equals(name.strip())
                || !name.toLowerCase(Locale.ROOT).endsWith(".txt") || name.length() <= 4
                || name.endsWith(".") || !StandardCharsets.UTF_8.newEncoder().canEncode(name)) {
            throw new IllegalArgumentException("A safe .txt filename is required.");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || "<>:\"/\\|?*".indexOf(c) >= 0) {
                throw new IllegalArgumentException("Filename contains an unsafe character.");
            }
        }
        String device = name.substring(0, name.indexOf('.')).stripTrailing().toUpperCase(Locale.ROOT);
        if (Set.of("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$").contains(device)
                || device.matches("(?:COM|LPT)[1-9\u00b9\u00b2\u00b3]")) {
            throw new IllegalArgumentException("Reserved device names are not allowed.");
        }
    }

    private static boolean collides(String first, String second) {
        return Normalizer.normalize(first, Normalizer.Form.NFC)
                .equalsIgnoreCase(Normalizer.normalize(second, Normalizer.Form.NFC));
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireRegularFile(Path path) throws IOException {
        BasicFileAttributes info = attributes(path);
        if (!info.isRegularFile() || info.isSymbolicLink() || info.isOther()) {
            throw new IOException("Unsafe filesystem entry in managed collection.");
        }
    }

    private static void checkDirectories(Path directory) throws IOException {
        if (directory == null) { throw new IOException("A managed directory with an existing parent is required."); }
        for (Path current = directory; current != null; current = current.getParent()) {
            BasicFileAttributes info = attributes(current);
            if (!info.isDirectory() || info.isSymbolicLink() || info.isOther()
                    || !current.toRealPath().equals(current)) {
                throw new IOException("Linked or unsafe directories are not allowed.");
            }
        }
    }

    private void checkLocations() throws IOException {
        checkDirectories(root);
        checkDirectories(work);
        requireRegularFile(work.resolve("lock"));
        if (!Objects.equals(rootKey, attributes(root).fileKey())
                || !Objects.equals(workKey, attributes(work).fileKey())
                || !Objects.equals(lockKey, attributes(work.resolve("lock")).fileKey())) {
            throw new IOException("Managed storage location changed.");
        }
        checkWorkFiles();
    }

    private void checkWorkFiles() throws IOException {
        try (var entries = Files.newDirectoryStream(work)) {
            for (Path entry : entries) {
                if (!WORK_FILES.contains(entry.getFileName().toString())) {
                    throw new IOException("Unexpected managed staging entry; manual recovery is required.");
                }
                requireRegularFile(entry);
            }
        }
    }

    private void clearWorkFiles() throws IOException {
        checkWorkFiles();
        Files.deleteIfExists(work.resolve("next"));
        Files.deleteIfExists(work.resolve("previous"));
    }

    private static void stage(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) { channel.write(buffer); }
            channel.force(true);
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        // No unsafe copy/delete fallback when atomic moves are unavailable.
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void requireBytes(Path path, byte[] expected) throws IOException {
        if (!hasBytes(path, expected)) { throw new IOException("Document changed during update."); }
    }

    private boolean hasBytes(Path path, byte[] expected) throws IOException {
        if (expected == null) {
            return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        }
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Arrays.equals(expected, readFile(path));
    }

    private void ensureOpen() throws IOException {
        if (closed || collectionLock == null || !collectionLock.isValid()) { throw new IOException("Document store is closed."); }
    }

    private void ensureUsable() throws IOException {
        ensureOpen();
        if (recoveryRequired) { throw new IOException("Collection requires recovery and reload() before further changes."); }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            // Closing the channel releases the lifetime collection lock as well.
            lockChannel.close();
        }
    }
}
