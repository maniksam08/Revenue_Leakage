package dashboard;

import dashboard.DashboardDtos.MoneyAtRiskResponse;
import enums.ClaimStatus;
import enums.DiscrepancyType;
import repository.ClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final List<DiscrepancyType> RISK_TYPES =
            List.of(DiscrepancyType.SHORT_PAID, DiscrepancyType.UNPAID_COD);
    private static final List<ClaimStatus> UNRECOVERED = List.of(ClaimStatus.OPEN, ClaimStatus.SUBMITTED);

    private final ClaimRepository claimRepository;
    private final dashboard.MoneyAtRiskCalculator calculator;

    @Value("${reconciler.business-zone:Asia/Kolkata}")
    private String businessZone = "Asia/Kolkata";

    @Transactional(readOnly = true)
    public MoneyAtRiskResponse moneyAtRisk(String partner) {
        return moneyAtRisk(partner, LocalDate.now(ZoneId.of(businessZone)));
    }

    /** Overload with an explicit "today" so tests don't depend on the wall clock. */
    @Transactional(readOnly = true)
    public MoneyAtRiskResponse moneyAtRisk(String partner, LocalDate today) {
        AgeBucket.Cutoffs cut = AgeBucket.cutoffs(today, ZoneId.of(businessZone));
        String wanted = partner == null || partner.isBlank() ? null : partner.trim();

        List<RiskRow> rows = claimRepository.aggregateRisk(RISK_TYPES, UNRECOVERED, cut.cut30(), cut.cut60()).stream()
                .filter(a -> wanted == null || wanted.equalsIgnoreCase(a.getPartnerName()))
                .map(a -> new RiskRow(a.getPartnerName(), a.getDiscrepancyType(),
                        nz(a.getCount0To30()), a.getAmount0To30(),
                        nz(a.getCount31To60()), a.getAmount31To60(),
                        nz(a.getCount61Plus()), a.getAmount61Plus()))
                .toList();
        return calculator.build(today, rows);
    }

    private static long nz(Long v) { return v == null ? 0 : v; }
}
