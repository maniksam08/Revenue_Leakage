package dashboard;


public final class CsvSanitizer {

    private CsvSanitizer() {}

    public static String neutralise(String text) {
        if (text == null || text.isEmpty()) return "";
        char first = text.charAt(0);
        return (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r')
                ? "'" + text : text;
    }
}
