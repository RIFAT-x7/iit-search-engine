import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TextProcessor {
    private static final String[] STOP_WORDS = {
        "a", "an", "the", "and", "or", "is", "are", "of", "to", "in", "for", "with"
    };

    public List<String> process(String text) {
        List<String> tokens = new ArrayList<>();
        String lowercase = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();

        // Scan characters manually. Punctuation separates words instead of joining them.
        for (int i = 0; i <= lowercase.length(); i++) {
            char character = i < lowercase.length() ? lowercase.charAt(i) : ' ';
            if ((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')) {
                word.append(character);
            } else if (word.length() > 0) {
                String token = word.toString();
                if (!isStopWord(token)) {
                    tokens.add(token);
                }
                word.setLength(0);
            }
        }
        return tokens;
    }

    private boolean isStopWord(String token) {
        for (String stopWord : STOP_WORDS) {
            if (token.equals(stopWord)) {
                return true;
            }
        }
        return false;
    }
}
