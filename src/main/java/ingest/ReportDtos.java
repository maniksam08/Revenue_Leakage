package ingest;

import entity.MappingProfile;
import enums.ReportType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ReportDtos {

    private ReportDtos() {}

    public enum Confidence {
        HIGH, MEDIUM, LOW, NONE;

        public static Confidence of(double s) {
            return s >= 0.9 ? HIGH : s >= 0.7 ? MEDIUM : s >= 0.55 ? LOW : NONE;
        }
    }

    public record DetectedColumn(int index, String header, CanonicalField.ValueType inferredType, int nonBlankSamples) {}

    public record Alternative(int columnIndex, String column, double confidence) {}

    public record MappingSuggestion(String canonicalField, boolean required, Integer columnIndex, String sourceColumn,
                                    double confidence, Confidence level, List<Alternative> alternatives) {}

    public record PreviewGrid(List<String> columns, List<List<String>> rows, int rowCount) {}

    public record ExistingProfileInfo(Long id, int version, Map<String, String> mappings,
                                      boolean applicableToThisFile, List<String> missingHeaders) {}

    public record UploadPreviewResponse(
            String partnerId, ReportType reportType, boolean reportTypeAutoDetected,
            String fileName, String fileFormat, String sheetName, int preambleRows,
            List<DetectedColumn> detectedColumns,
            List<MappingSuggestion> suggestedMappings,
            List<String> missingRequiredFields,
            List<String> unmappedColumns,
            PreviewGrid preview,
            ExistingProfileInfo existingProfile,
            List<String> warnings) {}

    public record MappingProfileResponse(Long id, String partnerId, ReportType reportType, int version, boolean active,
                                         Map<String, String> mappings, int preambleRows, String approvedBy,
                                         Instant createdAt) {
        public static MappingProfileResponse from(MappingProfile p) {
            return new MappingProfileResponse(p.getId(), p.getPartnerId(), p.getReportType(), p.getVersion(),
                    p.isActive(), p.getColumnMappings(), p.getPreambleRows(), p.getApprovedBy(), p.getCreatedAt());
        }
    }
}
