public class Document {
    private final int id;
    private final String filename;

    public Document(int id, String filename) {
        this.id = id;
        this.filename = filename;
    }

    public int getId() { return id; }
    public String getFilename() { return filename; }
}
