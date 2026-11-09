package controller;


import dashboard.*;
import enums.ClaimStatus;
import enums.DiscrepancyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Finance executive and co-founder views. Requires @EnableMethodSecurity in the application's security config. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','CO_FOUNDER')")
public class FinancialDashboardController {

    private final DashboardService dashboardService;
    private final ClaimQueryService claimQueryService;
    private final EvidenceExportService evidenceExportService;

    /** Unrecovered short-pays and unremitted COD, by partner and order age (0-30, 31-60, 61+ days). */
    @GetMapping("/api/v1/dashboard/money-at-risk")
    public DashboardDtos.MoneyAtRiskResponse moneyAtRisk(
            @RequestParam(required = false) @Size(min = 1, max = 64, message = "partner must be 1-64 characters") String partner) {
        return dashboardService.moneyAtRisk(partner);
    }

    @GetMapping("/api/v1/claims")
    public ClaimDtos.PageResponse<ClaimDtos.ClaimResponse> claims(
            @RequestParam(required = false) @Size(min = 1, max = 64, message = "partner must be 1-64 characters") String partner,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) DiscrepancyType type,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be >= 0") int page,
            @RequestParam(defaultValue = "25") @Min(value = 1, message = "size must be >= 1")
                    @Max(value = 200, message = "size must be <= 200") int size,
            @RequestParam(defaultValue = "CREATED_AT") ClaimSortField sortBy,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction) {
        return claimQueryService.list(partner, status, type, page, size, sortBy, direction);
    }

    /** Read-only (does not change claim status): builds a downloadable bundle to send to the partner. */
    @PostMapping("/api/v1/claims/{id}/export-evidence")
    public ResponseEntity<byte[]> exportEvidence(
            @PathVariable @Min(value = 1, message = "id must be positive") Long id,
            @Valid @RequestBody ClaimDtos.ExportEvidenceRequest request) {
        ClaimDtos.ExportedFile file = evidenceExportService.export(id, request.format());
        return ResponseEntity.ok()
                .contentType(file.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")      // financial data: never cache
                .body(file.content());
    }
}
