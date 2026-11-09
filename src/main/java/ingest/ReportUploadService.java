package ingest;

import enums.ReportType;
import ingest.ColumnMappingSuggester.ColumnProfile;
import ingest.ReportDtos.DetectedColumn;
import ingest.ReportDtos.ExistingProfileInfo;
import ingest.ReportDtos.MappingSuggestion;
import ingest.ReportDtos.PreviewGrid;
import ingest.ReportDtos.UploadPreviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;


@Service
@RequiredArgsConstructor
public class ReportUploadService {

    private final ReportFileReader reader;
    private final ColumnMappingSuggester suggester;
    private final MappingProfileService profiles;

    public UploadPreviewResponse preview(MultipartFile file, String partnerIdRaw, ReportType requestedType) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A non-empty file is required");
        }
        String partnerId = MappingProfileService.normalizePartner(partnerIdRaw);

        ParsedSheet sheet = reader.read(file);
        List<ColumnProfile> cols = suggester.profile(sheet);

        boolean autoDetected = requestedType == null;
        ReportType type = autoDetected ? suggester.detectType(cols) : requestedType;
        List<MappingSuggestion> suggestions = suggester.suggest(type, cols);

        Set<Integer> usedCols = new HashSet<>();
        suggestions.stream().filter(s -> s.columnIndex() != null).forEach(s -> usedCols.add(s.columnIndex()));
        List<String> missingRequired = suggestions.stream()
                .filter(s -> s.required() && s.columnIndex() == null).map(MappingSuggestion::canonicalField).toList();
        List<String> unmapped = cols.stream().filter(c -> !usedCols.contains(c.index())).map(ColumnProfile::header).toList();

        List<DetectedColumn> detected = cols.stream()
                .map(c -> new DetectedColumn(c.index(), c.header(), c.type(), c.nonBlank())).toList();
        PreviewGrid grid = new PreviewGrid(sheet.headers(), sheet.rows(), sheet.rows().size());

        ExistingProfileInfo existing = profiles.findActive(partnerId, type)
                .map(p -> {
                    var r = profiles.resolve(p, sheet.headers());
                    return new ExistingProfileInfo(p.getId(), p.getVersion(), p.getColumnMappings(),
                            r.complete(), r.missingHeaders());
                }).orElse(null);

        return new UploadPreviewResponse(partnerId, type, autoDetected,
                fileName(file), sheet.format(), sheet.sheetName(), sheet.preambleRows(),
                detected, suggestions, missingRequired, unmapped, grid, existing,
                warnings(sheet, suggestions, autoDetected, type, existing));
    }

    private List<String> warnings(ParsedSheet sheet, List<MappingSuggestion> suggestions, boolean autoDetected,
                                  ReportType type, ExistingProfileInfo existing) {
        List<String> w = new ArrayList<>();
        if (autoDetected) {
            w.add("Report type was auto-detected as " + type + "; pass reportType to override.");
        }
        Set<String> seen = new HashSet<>();
        sheet.headers().stream().filter(h -> !seen.add(ColumnMappingSuggester.key(h))).distinct().forEach(h ->
                w.add("Duplicate header '" + h + "': saved mappings match the first occurrence only."));
        if (sheet.rows().isEmpty()) {
            w.add("The file has a header but no data rows.");
        }
        suggestions.stream()
                .filter(s -> s.required() && s.columnIndex() != null && s.confidence() < 0.7)
                .forEach(s -> w.add("Low-confidence match for required field '" + s.canonicalField()
                        + "' -> '" + s.sourceColumn() + "'. Please confirm."));
        suggestions.stream()
                .filter(s -> "charged_weight".equals(s.canonicalField()) && s.sourceColumn() != null)
                .filter(s -> s.sourceColumn().toLowerCase(Locale.ROOT).matches(".*\\b(kg|kgs|kilo\\w*)\\b.*"))
                .forEach(s -> w.add("Weight column '" + s.sourceColumn() + "' looks like kilograms; rate cards use grams."));
        if (existing != null && !existing.applicableToThisFile()) {
            w.add("The saved profile (v" + existing.version() + ") no longer matches this file's headers: "
                    + existing.missingHeaders());
        }
        return w;
    }

    private static String fileName(MultipartFile f) {
        String n = StringUtils.getFilename(f.getOriginalFilename());
        return n == null || n.isBlank() ? "upload" : n;
    }
}
