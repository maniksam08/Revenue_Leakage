package dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {}

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /** Money split by order age. All amounts are INR with 2 decimals. */
    public record BucketedAmount(BigDecimal amount0To30, BigDecimal amount31To60, BigDecimal amount61Plus,
                                 BigDecimal total, long claimCount) {

        public static final BucketedAmount ZERO =
                new BucketedAmount(money(null), money(null), money(null), money(null), 0);

        static BucketedAmount of(RiskRow r) {
            BigDecimal a = money(r.amount0To30());
            BigDecimal b = money(r.amount31To60());
            BigDecimal c = money(r.amount61Plus());
            return new BucketedAmount(a, b, c, a.add(b).add(c), r.count0To30() + r.count31To60() + r.count61Plus());
        }

        public BucketedAmount plus(BucketedAmount o) {
            return new BucketedAmount(amount0To30.add(o.amount0To30), amount31To60.add(o.amount31To60),
                    amount61Plus.add(o.amount61Plus), total.add(o.total), claimCount + o.claimCount);
        }
    }

    public record PartnerRisk(String partner, BucketedAmount shortPaid, BucketedAmount unremittedCod,
                              BucketedAmount total) {}

    public record MoneyAtRiskResponse(LocalDate asOf, String currency, String ageBasis,
                                      List<PartnerRisk> partners,
                                      BucketedAmount shortPaid, BucketedAmount unremittedCod, BucketedAmount total) {}
}
