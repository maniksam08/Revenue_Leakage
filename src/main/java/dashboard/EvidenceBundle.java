package dashboard;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Partner-facing evidence for one claim. Contains no internal raw payloads. */
public record EvidenceBundle(Meta meta, ClaimInfo claim, OrderInfo order, List<SettlementInfo> settlements,
                             List<FreightInfo> freightInvoices, JsonNode calculation, List<String> notes) {

    public record Meta(String bundleVersion, Instant generatedAt, String currency) {}

    public record ClaimInfo(Long claimId, String orderId, String partner, String discrepancyType,
                            BigDecimal claimedAmount, String status, Instant raisedAt) {}

    public record OrderInfo(String orderId, String channel, BigDecimal totalValue, String paymentMode,
                            String deliveryStatus, BigDecimal expectedAmount, BigDecimal receivedAmount,
                            Instant orderDate) {}

    public record SettlementInfo(String channel, BigDecimal itemPrice, BigDecimal marketplaceFee, BigDecimal gstTcs,
                                 BigDecimal incomeTaxTds, BigDecimal netPayout, LocalDate settlementDate) {}

    public record FreightInfo(String courierName, Integer chargedWeightG, Integer appliedWeightSlab,
                              BigDecimal baseCharge, LocalDate invoiceDate) {}
}
