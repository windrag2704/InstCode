import java.util.HashMap;

public class ConfigParser {
    private static final HashMap<String, String> parsed = new HashMap<>();
    public static void parse(String line) {
        parsed.put(line.split(" ")[0], line.split(" ")[1]);
    }
    public static HashMap<String, String> getParsed() {
        return parsed;
    }
}
