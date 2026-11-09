package dashboard;

import dashboard.ClaimDtos.ClaimResponse;
import dashboard.ClaimDtos.PageResponse;
import entity.Claim;
import enums.ClaimStatus;
import enums.DiscrepancyType;
import repository.ClaimRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClaimQueryService {

    private final ClaimRepository claimRepository;

    @Transactional(readOnly = true)
    public PageResponse<ClaimResponse> list(String partner, ClaimStatus status, DiscrepancyType type,
                                            int page, int size, ClaimSortField sortBy, Sort.Direction direction) {
        String partnerFilter = partner == null || partner.isBlank() ? null : partner.trim();

        // Exact match on partner (uses idx_claims_partner_status); the dashboard returns names as stored.
        Specification<Claim> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>(3);
            if (partnerFilter != null) ps.add(cb.equal(root.get("partnerName"), partnerFilter));
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (type != null) ps.add(cb.equal(root.get("discrepancyType"), type));
            return cb.and(ps.toArray(new Predicate[0]));
        };

        // id as tie-breaker keeps page boundaries stable when many claims share the sort value
        Sort sort = Sort.by(direction, sortBy.property()).and(Sort.by(direction, "id"));
        Page<Claim> result = claimRepository.findAll(spec, PageRequest.of(page, size, sort));
        return PageResponse.from(result, ClaimResponse::from);
    }
}
