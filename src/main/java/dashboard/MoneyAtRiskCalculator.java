package dashboard;

import dashboard.DashboardDtos.BucketedAmount;
import dashboard.DashboardDtos.MoneyAtRiskResponse;
import dashboard.DashboardDtos.PartnerRisk;
import enums.DiscrepancyType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure roll-up of database aggregates into the dashboard response. No I/O, no clock: easy to unit test. */
@Component
public class MoneyAtRiskCalculator {

    private static final class Acc {
        final String name;
        BucketedAmount shortPaid = BucketedAmount.ZERO;
        BucketedAmount cod = BucketedAmount.ZERO;
        Acc(String name) { this.name = name; }
    }

    public MoneyAtRiskResponse build(LocalDate asOf, List<RiskRow> rows) {
        Map<String, Acc> byPartner = new LinkedHashMap<>();
        for (RiskRow r : rows) {
            if (r.type() != DiscrepancyType.SHORT_PAID && r.type() != DiscrepancyType.UNPAID_COD) {
                continue;                                              // only the two money-at-risk types
            }
            String name = r.partner() == null || r.partner().isBlank() ? "UNKNOWN" : r.partner().trim();
            Acc acc = byPartner.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new Acc(name));
            BucketedAmount amount = BucketedAmount.of(r);
            if (r.type() == DiscrepancyType.SHORT_PAID) {
                acc.shortPaid = acc.shortPaid.plus(amount);
            } else {
                acc.cod = acc.cod.plus(amount);
            }
        }

        List<PartnerRisk> partners = byPartner.values().stream()
                .map(a -> new PartnerRisk(a.name, a.shortPaid, a.cod, a.shortPaid.plus(a.cod)))
                .sorted(Comparator.comparing((PartnerRisk p) -> p.total().total()).reversed()
                        .thenComparing(PartnerRisk::partner, String.CASE_INSENSITIVE_ORDER))
                .toList();

        BucketedAmount shortPaid = partners.stream().map(PartnerRisk::shortPaid)
                .reduce(BucketedAmount.ZERO, BucketedAmount::plus);
        BucketedAmount cod = partners.stream().map(PartnerRisk::unremittedCod)
                .reduce(BucketedAmount.ZERO, BucketedAmount::plus);

        return new MoneyAtRiskResponse(asOf, "INR",
                "Days since order date (business time zone); unrecovered claims only (OPEN, SUBMITTED)",
                partners, shortPaid, cod, shortPaid.plus(cod));
    }
}
