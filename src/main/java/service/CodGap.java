package service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CodGap {

    private CodGap() {}

    public static BigDecimal gap(BigDecimal expectedAmount, BigDecimal totalValue, BigDecimal receivedAmount) {
        BigDecimal expected = expectedAmount != null ? expectedAmount : (totalValue != null ? totalValue : BigDecimal.ZERO);
        BigDecimal received = receivedAmount != null ? receivedAmount : BigDecimal.ZERO;
        BigDecimal gap = expected.subtract(received);
        return gap.signum() > 0 ? gap.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
    }
}
