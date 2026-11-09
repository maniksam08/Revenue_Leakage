package ingest;

import enums.ReportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Map;

public record ApproveMappingRequest(
        @NotBlank String partnerId,
        @NotNull ReportType reportType,
        @NotEmpty Map<String, String> mappings,
        @PositiveOrZero Integer preambleRows) {}
