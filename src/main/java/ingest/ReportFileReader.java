package ingest;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class ReportFileReader {

    public static final int MAX_DATA_ROWS = 20;
    private static final int HEADER_SCAN_ROWS = 10;
    private static final int MAX_RAW_ROWS = 40;          // leaves room for blank/preamble rows
    private static final int MAX_CELL_CHARS = 200;

    private enum Format { CSV, XLSX, XLS }

    public ParsedSheet read(MultipartFile file) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("report-upload-", ".tmp");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Format format = detectFormat(tmp, file.getOriginalFilename());
            return switch (format) {
                case CSV -> toParsed(readCsv(tmp), "CSV", null);
                case XLSX -> readXlsx(tmp);
                case XLS -> readXls(tmp);
            };
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Could not read the uploaded file", e);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            }
        }
    }

    // ------------------------------------------------------------------ format detection

    private Format detectFormat(Path tmp, String originalName) throws IOException {
        byte[] head = new byte[1024];
        int n;
        try (InputStream in = Files.newInputStream(tmp)) {
            n = in.readNBytes(head, 0, head.length);
        }
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded file is empty");
        }
        if (n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            return Format.XLSX;
        }
        if (n >= 4 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0) {
            return Format.XLS;
        }
        String name = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        boolean textName = name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt");
        boolean hasNul = false;
        for (int i = 0; i < n; i++) {
            if (head[i] == 0) { hasNul = true; break; }
        }
        if (!textName || hasNul) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported file. Upload a .csv, .tsv, .xlsx or .xls report");
        }
        return Format.CSV;
    }

    // ------------------------------------------------------------------ CSV (OpenCSV)

    private List<List<String>> readCsv(Path tmp) throws IOException {
        // InputStreamReader replaces malformed bytes instead of failing on Windows-1252 exports
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(Files.newInputStream(tmp), StandardCharsets.UTF_8))) {
            br.mark(1);
            if (br.read() != 0xFEFF) {            // consume a UTF-8 BOM if present
                br.reset();
            }
            br.mark(65_536);
            String firstLine = br.readLine();
            br.reset();
            char sep = sniffSeparator(firstLine == null ? "" : firstLine);

            CSVParser parser = new CSVParserBuilder().withSeparator(sep).build();
            List<List<String>> raw = new ArrayList<>();
            try (CSVReader reader = new CSVReaderBuilder(br).withCSVParser(parser).build()) {
                String[] row;
                while (raw.size() < MAX_RAW_ROWS && (row = reader.readNext()) != null) {
                    raw.add(Arrays.asList(row));
                }
            } catch (CsvValidationException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Malformed CSV: " + e.getMessage(), e);
            }
            return raw;
        }
    }

    private static char sniffSeparator(String line) {
        char[] candidates = {',', ';', '\t', '|'};
        int[] counts = new int[candidates.length];
        boolean inQuotes = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') { inQuotes = !inQuotes; continue; }
            if (inQuotes) continue;
            for (int i = 0; i < candidates.length; i++) {
                if (ch == candidates[i]) counts[i]++;
            }
        }
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) best = i;
        }
        return counts[best] == 0 ? ',' : candidates[best];
    }

    // ------------------------------------------------------------------ XLSX (POI SAX, stops after N rows)

    private ParsedSheet readXlsx(Path tmp) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(tmp.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();

            while (sheets.hasNext()) {
                try (InputStream sheetStream = sheets.next()) {
                    String sheetName = sheets.getSheetName();
                    RowCollector collector = new RowCollector(MAX_RAW_ROWS);
                    XMLReader xml = XMLHelper.newXMLReader();
                    xml.setContentHandler(new XSSFSheetXMLHandler(
                            styles, null, strings, collector, new DataFormatter(Locale.ENGLISH), false));
                    try {
                        xml.parse(new InputSource(sheetStream));
                    } catch (StopParsing stop) {
                        // expected: we have all the rows we need
                    } catch (SAXException e) {
                        if (!causedByStop(e)) throw e;
                    }
                    if (collector.rows.stream().anyMatch(r -> r.stream().anyMatch(c -> !c.isBlank()))) {
                        return toParsed(collector.rows, "XLSX", sheetName);
                    }
                }
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The workbook has no data");
        } catch (OpenXML4JException | SAXException | ParserConfigurationException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not read the .xlsx file (corrupt, or not a real Excel workbook)", e);
        }
    }

    private static boolean causedByStop(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof StopParsing) return true;
        }
        return false;
    }

    private static final class StopParsing extends RuntimeException {
        StopParsing() { super(null, null, false, false); }   // no stack trace: used for control flow
    }

    private static final class RowCollector implements XSSFSheetXMLHandler.SheetContentsHandler {
        final List<List<String>> rows = new ArrayList<>();
        private final int limit;
        private List<String> current;

        RowCollector(int limit) { this.limit = limit; }

        @Override public void startRow(int rowNum) { current = new ArrayList<>(); }

        @Override public void endRow(int rowNum) {
            rows.add(current);
            if (rows.size() >= limit) throw new StopParsing();
        }

        @Override public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (cellReference == null) return;
            int col = new CellReference(cellReference).getCol();
            while (current.size() <= col) current.add("");     // SAX skips empty cells; re-align by column
            current.set(col, formattedValue == null ? "" : formattedValue);
        }

        @Override public void headerFooter(String text, boolean isHeader, String tagName) { /* ignored */ }
    }

    // ------------------------------------------------------------------ XLS (legacy, small by format limits)

    private ParsedSheet readXls(Path tmp) throws IOException {
        try (Workbook wb = WorkbookFactory.create(tmp.toFile(), null, true)) {
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                List<List<String>> raw = new ArrayList<>();
                for (Row row : sheet) {
                    int last = row.getLastCellNum();
                    List<String> cells = new ArrayList<>(Math.max(last, 0));
                    for (int c = 0; c < last; c++) {
                        var cell = row.getCell(c);
                        String v = "";
                        if (cell != null) {
                            try { v = fmt.formatCellValue(cell, evaluator); }
                            catch (RuntimeException e) { v = cell.toString(); }
                        }
                        cells.add(v);
                    }
                    raw.add(cells);
                    if (raw.size() >= MAX_RAW_ROWS) break;
                }
                if (raw.stream().anyMatch(r -> r.stream().anyMatch(c -> !c.isBlank()))) {
                    return toParsed(raw, "XLS", sheet.getSheetName());
                }
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The workbook has no data");
        } catch (EncryptedDocumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The workbook is password-protected; upload an unprotected copy", e);
        }
    }

    // ------------------------------------------------------------------ header detection + shaping

    private ParsedSheet toParsed(List<List<String>> raw, String format, String sheetName) {
        List<List<String>> rows = raw.stream()
                .filter(r -> r.stream().anyMatch(c -> c != null && !c.isBlank()))
                .toList();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The file contains no data rows");
        }

        // Header = first row (within the top 10) that is nearly as wide as the widest of them.
        // This skips one- or two-cell title lines such as "Settlement report 01 Oct - 31 Oct".
        int scan = Math.min(HEADER_SCAN_ROWS, rows.size());
        int[] counts = new int[scan];
        int max = 0;
        for (int i = 0; i < scan; i++) {
            counts[i] = (int) rows.get(i).stream().filter(c -> c != null && !c.isBlank()).count();
            max = Math.max(max, counts[i]);
        }
        int threshold = max >= 2 ? Math.max(2, (int) Math.ceil(max * 0.6)) : 1;
        int headerIdx = 0;
        for (int i = 0; i < scan; i++) {
            if (counts[i] >= threshold) { headerIdx = i; break; }
        }

        List<String> headerRow = rows.get(headerIdx);
        int width = headerRow.size();
        while (width > 0 && clean(headerRow.get(width - 1)).isEmpty()) width--;   // trailing empty columns
        List<String> headers = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            String h = clean(headerRow.get(i));
            headers.add(h.isEmpty() ? "Column " + (i + 1) : h);
        }

        int end = Math.min(rows.size(), headerIdx + 1 + MAX_DATA_ROWS);
        List<List<String>> data = new ArrayList<>(end - headerIdx - 1);
        for (List<String> row : rows.subList(headerIdx + 1, end)) {
            List<String> padded = new ArrayList<>(width);
            for (int i = 0; i < width; i++) {
                String v = i < row.size() && row.get(i) != null ? row.get(i).trim() : "";
                padded.add(v.length() > MAX_CELL_CHARS ? v.substring(0, MAX_CELL_CHARS) + "…" : v);
            }
            data.add(padded);
        }
        return new ParsedSheet(format, sheetName, headerIdx, headers, data);
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("\uFEFF", "").replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }
}
