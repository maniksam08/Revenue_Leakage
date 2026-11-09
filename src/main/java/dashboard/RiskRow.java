package dashboard;

import enums.DiscrepancyType;

import java.math.BigDecimal;

/** One (partner, discrepancy type) aggregate as returned by the database. */
public record RiskRow(String partner, DiscrepancyType type,
                      long count0To30, BigDecimal amount0To30,
                      long count31To60, BigDecimal amount31To60,
                      long count61Plus, BigDecimal amount61Plus) {}
