package ingest;

import enums.ReportType;
import ingest.CanonicalField.ValueType;
import ingest.ReportDtos.Alternative;
import ingest.ReportDtos.Confidence;
import ingest.ReportDtos.MappingSuggestion;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;


@Component
public class ColumnMappingSuggester {

    static final double MIN_SUGGEST = 0.55;
    static final double MIN_ALTERNATIVE = 0.40;

    public record ColumnProfile(int index, String header, ValueType type, int nonBlank, double distinctRatio) {}

    // ------------------------------------------------------------------ public API

    public List<ColumnProfile> profile(ParsedSheet sheet) {
        List<ColumnProfile> out = new ArrayList<>(sheet.headers().size());
        for (int i = 0; i < sheet.headers().size(); i++) {
            List<String> values = new ArrayList<>();
            for (List<String> row : sheet.rows()) {
                String v = row.get(i);
                if (!isBlankish(v)) values.add(v.trim());
            }
            double distinct = values.isEmpty() ? 1.0 : (double) new HashSet<>(values).size() / values.size();
            out.add(new ColumnProfile(i, sheet.headers().get(i), inferType(values), values.size(), distinct));
        }
        return out;
    }

    public List<MappingSuggestion> suggest(ReportType type, List<ColumnProfile> cols) {
        record Cand(CanonicalField field, ColumnProfile col, double score) {}

        List<CanonicalField> fields = CanonicalField.forType(type);
        List<Cand> all = new ArrayList<>();
        for (CanonicalField f : fields) {
            for (ColumnProfile c : cols) {
                double s = adjust(f, headerScore(f, c.header()), c);
                if (s >= MIN_ALTERNATIVE) all.add(new Cand(f, c, s));
            }
        }
        all.sort((a, b) -> {
            int cmp = Double.compare(b.score(), a.score());
            return cmp != 0 ? cmp : Integer.compare(a.col().index(), b.col().index());
        });

        Map<CanonicalField, Cand> chosen = new EnumMap<>(CanonicalField.class);
        Set<Integer> usedCols = new HashSet<>();
        for (Cand c : all) {
            if (c.score() < MIN_SUGGEST) break;                     // list is sorted: nothing better follows
            if (chosen.containsKey(c.field()) || usedCols.contains(c.col().index())) continue;
            chosen.put(c.field(), c);
            usedCols.add(c.col().index());
        }

        List<MappingSuggestion> result = new ArrayList<>(fields.size());
        for (CanonicalField f : fields) {
            Cand ch = chosen.get(f);
            List<Alternative> alts = all.stream()
                    .filter(x -> x.field() == f && (ch == null || x.col().index() != ch.col().index()))
                    .limit(3)
                    .map(x -> new Alternative(x.col().index(), x.col().header(), round(x.score())))
                    .toList();
            result.add(ch == null
                    ? new MappingSuggestion(f.wire(), f.required(), null, null, 0.0, Confidence.NONE, alts)
                    : new MappingSuggestion(f.wire(), f.required(), ch.col().index(), ch.col().header(),
                            round(ch.score()), Confidence.of(ch.score()), alts));
        }
        return result;
    }

    /** Used when the caller does not say whether the file is a settlement or a freight report. */
    public ReportType detectType(List<ColumnProfile> cols) {
        ReportType best = ReportType.SETTLEMENT;
        double bestScore = -1;
        for (ReportType t : ReportType.values()) {
            double total = suggest(t, cols).stream()
                    .filter(s -> s.columnIndex() != null)
                    .mapToDouble(s -> s.confidence() * (s.required() ? 1.5 : 1.0))
                    .sum();
            if (total > bestScore + 1e-9) {
                bestScore = total;
                best = t;
            }
        }
        return best;
    }

    /** Case/punctuation-insensitive identity of a header; used to match saved profiles to later files. */
    public static String key(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------------ header similarity

    private static final Set<String> NOISE = Set.of("inr", "rs", "rupee", "rupees", "in", "of", "the", "a", "an",
            "g", "gm", "gms", "gram", "grams", "kg", "kgs");

    private static final Map<String, String> ABBREV = Map.ofEntries(
            Map.entry("ord", "order"), Map.entry("no", "number"), Map.entry("num", "number"),
            Map.entry("nbr", "number"), Map.entry("ref", "reference"), Map.entry("amt", "amount"),
            Map.entry("wt", "weight"), Map.entry("wgt", "weight"), Map.entry("dt", "date"),
            Map.entry("chg", "charge"), Map.entry("chrg", "charge"), Map.entry("stl", "settlement"),
            Map.entry("settle", "settlement"), Map.entry("settled", "settlement"), Map.entry("comm", "commission"),
            Map.entry("txn", "transaction"));

    private double headerScore(CanonicalField f, String header) {
        String hCompact = key(header);
        Set<String> hTokens = tokens(header);
        double best = 0;
        for (String alias : f.aliases()) {
            String aCompact = key(alias);
            double s;
            if (hCompact.equals(aCompact)) {
                s = 1.0;
            } else {
                double tok = jaccard(hTokens, tokens(alias));
                double edit = (hCompact.length() >= 5 && aCompact.length() >= 5)
                        ? levenshteinSimilarity(hCompact, aCompact) : 0;
                s = Math.max(tok, edit >= 0.75 ? edit * 0.95 : 0);
            }
            best = Math.max(best, s);
        }
        return best;
    }

    static Set<String> tokens(String raw) {
        String s = raw.replace('\u00A0', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");   // OrderID -> Order ID
        Set<String> out = new LinkedHashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.isEmpty() || NOISE.contains(t)) continue;
            t = ABBREV.getOrDefault(t, t);
            if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) t = t.substring(0, t.length() - 1);
            out.add(t);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private static double levenshteinSimilarity(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }

    // ------------------------------------------------------------------ value-based adjustment

    private double adjust(CanonicalField f, double s, ColumnProfile c) {
        if (s == 0 || c.nonBlank() == 0) return s;     // nothing to check against
        switch (f.expected()) {
            case NUMERIC:
                return c.type() == ValueType.NUMERIC ? Math.min(1.0, s + 0.05) : s * 0.5;
            case DATE:
                return c.type() == ValueType.DATE ? Math.min(1.0, s + 0.05) : s * 0.5;
            case TEXT:
                return (c.type() == ValueType.NUMERIC || c.type() == ValueType.DATE) ? s * 0.6 : s;
            default:
                if (f == CanonicalField.ORDER_ID) {
                    if (c.type() == ValueType.DATE) return s * 0.5;
                    if (c.nonBlank() >= 5 && c.distinctRatio() < 0.5) return s * 0.8;   // ids should be mostly unique
                }
                return s;
        }
    }

    // ------------------------------------------------------------------ value typing

    private static final Set<String> NULLISH = Set.of("-", "--", "na", "n/a", "null", "none", "nan");
    private static final Pattern NUMBER = Pattern.compile("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$");
    private static final List<DateTimeFormatter> DATE_FORMATS = buildDateFormats();

    private static List<DateTimeFormatter> buildDateFormats() {
        List<DateTimeFormatter> l = new ArrayList<>(Stream.of(
                "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "MM/dd/yyyy", "dd-MMM-yyyy", "dd MMM yyyy", "d MMM yyyy",
                "yyyy/MM/dd", "dd/MM/yy", "d/M/yyyy", "d-M-yyyy", "dd-MMM-yy", "MMM d, yyyy",
                "yyyy-MM-dd HH:mm:ss", "dd-MM-yyyy HH:mm:ss", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm")
                .map(p -> new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p)
                        .toFormatter(Locale.ENGLISH))
                .toList());
        l.add(DateTimeFormatter.ISO_DATE_TIME);
        return l;
    }

    private static boolean isBlankish(String v) {
        return v == null || v.isBlank() || NULLISH.contains(v.trim().toLowerCase(Locale.ROOT));
    }

    private static ValueType inferType(List<String> values) {
        if (values.isEmpty()) return ValueType.TEXT;
        long numeric = values.stream().filter(ColumnMappingSuggester::isNumber).count();
        if (numeric >= values.size() * 0.8) return ValueType.NUMERIC;
        long dates = values.stream().filter(ColumnMappingSuggester::isDate).count();
        if (dates >= values.size() * 0.8) return ValueType.DATE;
        return ValueType.TEXT;
    }

    private static boolean isNumber(String v) {
        String t = v.replace(",", "").replace("₹", "").replace("$", "")
                .replaceAll("(?i)^\\s*(rs\\.?|inr)\\s*", "").trim();
        if (t.startsWith("(") && t.endsWith(")")) t = "-" + t.substring(1, t.length() - 1).trim();   // (1,234.00)
        return NUMBER.matcher(t).matches();
    }

    private static boolean isDate(String v) {
        String t = v.trim();
        boolean hasDigit = false;
        for (int i = 0; i < t.length() && !hasDigit; i++) hasDigit = Character.isDigit(t.charAt(i));
        if (!hasDigit) return false;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                f.parse(t);
                return true;
            } catch (RuntimeException ignored) { /* try next */ }
        }
        return false;
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
