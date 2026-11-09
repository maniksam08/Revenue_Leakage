package service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import entity.Claim;
import entity.CustomerOrder;
import entity.FreightInvoice;
import entity.UnmatchedRecord;
import entity.UnmatchedRecord.Reason;
import entity.UnmatchedRecord.ReviewStatus;
import entity.UnmatchedRecord.SourceType;
import enums.ClaimStatus;
import enums.DiscrepancyType;
import repository.ClaimRepository;
import repository.CustomerOrderRepository;
import repository.FreightInvoiceRepository;
import repository.UnmatchedRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnremittedCodDetectionService {

    private static final int BATCH = 2_000;

    private final CustomerOrderRepository orderRepository;
    private final FreightInvoiceRepository freightRepository;
    private final ClaimRepository claimRepository;
    private final UnmatchedRecordRepository unmatchedRepository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    @Value("${reconciler.discrepancy-threshold:10.00}")
    private BigDecimal threshold = new BigDecimal("10.00");

    /** Couriers typically remit COD 2-7 days after delivery; we have no delivered_at, so age is from order date. */
    @Value("${reconciler.cod-remittance-grace-days:10}")
    private int graceDays = 10;

    public record DetectionSummary(int ordersScanned, int claimsCreated, int sentToReview, BigDecimal totalAmount) {}

    @Transactional
    public DetectionSummary detect() {
        Instant cutoff = Instant.now().minus(graceDays, ChronoUnit.DAYS);
        long afterId = 0;
        int scanned = 0, created = 0, reviewed = 0;
        BigDecimal total = BigDecimal.ZERO;

        while (true) {
            List<CustomerOrder> batch = orderRepository.findUnremittedCodBatch(
                    cutoff, afterId, threshold, PageRequest.of(0, BATCH));
            if (batch.isEmpty()) break;
            afterId = batch.get(batch.size() - 1).getId();
            scanned += batch.size();

            List<String> orderIds = batch.stream().map(CustomerOrder::getOrderId).toList();
            Map<String, FreightInvoice> courierByOrder = latestFreightByOrder(orderIds);
            Set<String> alreadyQueued = unmatchedRepository
                    .findByOrderIdInAndReasonAndStatus(orderIds, Reason.NO_COURIER_FOR_COD, ReviewStatus.PENDING)
                    .stream().map(UnmatchedRecord::getOrderId).collect(Collectors.toSet());

            List<Claim> claims = new ArrayList<>();
            List<UnmatchedRecord> reviews = new ArrayList<>();
            for (CustomerOrder o : batch) {
                BigDecimal gap = CodGap.gap(o.getExpectedAmount(), o.getTotalValue(), o.getReceivedAmount());
                if (gap.compareTo(threshold) <= 0) continue;                  // defensive: query already filters

                FreightInvoice f = courierByOrder.get(o.getOrderId());
                if (f == null) {
                    if (!alreadyQueued.contains(o.getOrderId())) reviews.add(reviewItem(o, gap));
                    continue;
                }
                claims.add(Claim.builder()
                        .orderId(o.getOrderId()).partnerName(f.getCourierName().trim())
                        .discrepancyType(DiscrepancyType.UNPAID_COD).discrepancyAmount(gap)
                        .status(ClaimStatus.OPEN).evidenceJson(evidence(o, f, gap)).build());
            }

            claimRepository.saveAll(claims);
            unmatchedRepository.saveAll(reviews);
            em.flush();
            batch.forEach(em::detach);
            courierByOrder.values().forEach(em::detach);
            claims.forEach(em::detach);
            reviews.forEach(em::detach);

            created += claims.size();
            reviewed += reviews.size();
            total = total.add(claims.stream().map(Claim::getDiscrepancyAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        log.info("COD detection: {} orders scanned, {} claims created (INR {}), {} sent to review",
                scanned, created, total, reviewed);
        return new DetectionSummary(scanned, created, reviewed, total);
    }

    /** Most recent freight invoice that names a courier, per order (forward leg wins over older rows). */
    private Map<String, FreightInvoice> latestFreightByOrder(List<String> orderIds) {
        Map<String, FreightInvoice> latest = new HashMap<>();
        for (FreightInvoice f : freightRepository.findByOrderIdIn(orderIds)) {
            if (f.getCourierName() == null || f.getCourierName().isBlank()) continue;
            latest.merge(f.getOrderId(), f, (a, b) -> {
                if (a.getInvoiceDate() == null) return b;
                if (b.getInvoiceDate() == null) return a;
                return b.getInvoiceDate().isAfter(a.getInvoiceDate()) ? b : a;
            });
        }
        return latest;
    }

    private String evidence(CustomerOrder o, FreightInvoice f, BigDecimal gap) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("paymentMode", "COD");
        ev.put("deliveryStatus", o.getDeliveryStatus());
        ev.put("orderDate", o.getCreatedAt() == null ? null : o.getCreatedAt().toString());
        ev.put("codValue", o.getExpectedAmount() != null ? o.getExpectedAmount() : o.getTotalValue());
        ev.put("receivedAmount", o.getReceivedAmount() == null ? BigDecimal.ZERO : o.getReceivedAmount());
        ev.put("unremittedAmount", gap);
        ev.put("courier", f.getCourierName());
        ev.put("freightInvoiceDate", f.getInvoiceDate() == null ? null : f.getInvoiceDate().toString());
        ev.put("remittanceGraceDays", graceDays);
        return toJson(ev);
    }

    private UnmatchedRecord reviewItem(CustomerOrder o, BigDecimal gap) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", o.getOrderId());
        p.put("channel", o.getChannel());
        p.put("unremittedAmount", gap);
        p.put("note", "Delivered COD order not remitted, but no freight invoice identifies the courier");
        return UnmatchedRecord.builder()
                .sourceType(SourceType.ORDER).reason(Reason.NO_COURIER_FOR_COD)
                .orderId(o.getOrderId()).channel(o.getChannel()).payloadJson(toJson(p)).build();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise JSON payload", e);
        }
    }
}
