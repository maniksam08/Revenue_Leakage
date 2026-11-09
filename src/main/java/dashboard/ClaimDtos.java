package dashboard;

import entity.Claim;
import enums.ClaimStatus;
import enums.DiscrepancyType;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

public final class ClaimDtos {

    private ClaimDtos() {}

    public enum ExportFormat { JSON, CSV }

    public record ExportEvidenceRequest(@NotNull(message = "format is required (JSON or CSV)") ExportFormat format) {}

    public record ExportedFile(byte[] content, MediaType mediaType, String filename) {}

    /** List view: deliberately excludes the (large) evidence JSON; use export-evidence for that. */
    public record ClaimResponse(Long id, String orderId, String partnerName, DiscrepancyType discrepancyType,
                                BigDecimal discrepancyAmount, ClaimStatus status, Instant createdAt) {
        public static ClaimResponse from(Claim c) {
            return new ClaimResponse(c.getId(), c.getOrderId(), c.getPartnerName(), c.getDiscrepancyType(),
                    c.getDiscrepancyAmount(), c.getStatus(), c.getCreatedAt());
        }
    }

    /** Stable page envelope (Spring's own Page serialisation is not a supported JSON contract). */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {
        public static <E, T> PageResponse<T> from(Page<E> p, Function<E, T> mapper) {
            return new PageResponse<>(p.getContent().stream().map(mapper).toList(),
                    p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages(), p.isLast());
        }
    }
}
