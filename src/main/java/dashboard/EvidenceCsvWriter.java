package dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencsv.CSVWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class EvidenceCsvWriter {

    private EvidenceCsvWriter() {}

    public static byte[] write(JsonNode bundle) {
        StringWriter sw = new StringWriter();
        sw.write('\uFEFF');
        try (CSVWriter w = new CSVWriter(sw, ',', '"', '"', "\r\n")) {
            w.writeNext(new String[]{"section", "record", "field", "value"});
            for (String[] row : rows(bundle)) {
                w.writeNext(row);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not build CSV", e);
        }
        return sw.toString().getBytes(StandardCharsets.UTF_8);
    }

    static List<String[]> rows(JsonNode root) {
        List<String[]> out = new ArrayList<>();
        root.fields().forEachRemaining(e -> walk(out, e.getKey(), 1, "", e.getValue()));
        return out;
    }

    private static void walk(List<String[]> out, String section, int record, String path, JsonNode n) {
        if (n.isObject()) {
            n.fields().forEachRemaining(f ->
                    walk(out, section, record, path.isEmpty() ? f.getKey() : path + "." + f.getKey(), f.getValue()));
        } else if (n.isArray()) {
            int i = 1;
            for (JsonNode el : n) {
                if (el.isContainerNode()) walk(out, section, i, path, el);
                else out.add(row(section, i, path, el));
                i++;
            }
        } else {
            out.add(row(section, record, path, n));
        }
    }

    private static String[] row(String section, int record, String path, JsonNode n) {
        String field = path.isEmpty() ? "value" : path;
        String value;
        if (n.isNull()) value = "";
        else if (n.isBigDecimal()) value = n.decimalValue().toPlainString();
        else if (n.isNumber() || n.isBoolean()) value = n.asText();
        else value = CsvSanitizer.neutralise(n.asText());
        return new String[]{CsvSanitizer.neutralise(section), String.valueOf(record), CsvSanitizer.neutralise(field), value};
    }
}
