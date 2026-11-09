package ingest;

import java.util.List;

public record ParsedSheet(String format, String sheetName, int preambleRows,
                          List<String> headers, List<List<String>> rows) {}
