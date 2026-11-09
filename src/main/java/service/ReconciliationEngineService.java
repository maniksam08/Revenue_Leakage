package service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import entity.Claim;
import entity.CustomerOrder;
import entity.FreightInvoice;
import entity.MarketplaceSettlement;
import entity.RateCard;
import entity.UnmatchedRecord;
import entity.UnmatchedRecord.Reason;
import entity.UnmatchedRecord.SourceType;
import enums.ClaimStatus;
import enums.DiscrepancyType;
import enums.PaymentMode;
import repository.ClaimRepository;
import repository.CustomerOrderRepository;
import repository.RateCardRepository;
import repository.UnmatchedRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class ReconciliationEngineService {

    private static final int DB_CHUNK = 2_000;     // keeps IN-lists far below PostgreSQL's 32,767 bind limit
    private static final int FLUSH_CHUNK = 1_000;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CustomerOrderRepository orderRepository;
    private final ClaimRepository claimRepository;
    private final RateCardRepository rateCardRepository;
    private final UnmatchedRecordRepository unmatchedRepository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** Gaps strictly greater than this (in INR) are flagged. */
    @Value("${reconciler.discrepancy-threshold:10.00}")
    private BigDecimal threshold = new BigDecimal("10.00");

    private volatile RateCardIndex rateCardIndex;

    // ------------------------------------------------------------------ public API

    public record ComputationResult(
            BigDecimal expectedNetPayout,          // after statutory deductions; null if not evaluated
            BigDecimal actualNetPayout,
            BigDecimal statutoryDeductions,        // gst_tcs + income_tax_tds, excluded from leakage
            BigDecimal expectedFreight,            // null if not evaluated
            List<Claim> draftClaims,
            Map<SourceType, Reason> reviewFlags) {}

    public record ReconciliationSummary(
            int settlementRows, int freightRows, int matchedOrders, int unmatchedRows,
            int claimsCreated, int claimsSkippedExisting, BigDecimal totalLeakage,
            Map<DiscrepancyType, BigDecimal> leakageByType, long elapsedMs) {}

    public ReconciliationSummary reconcile(List<MarketplaceSettlement> settlements, List<FreightInvoice> freights) {
        long t0 = System.nanoTime();
        RateCardIndex idx = refreshRateCards();

        // 1. Bulk-load candidate orders (chunked, index-backed, sequential on the tx connection).
        Map<String, CustomerOrder> orderById = loadOrders(settlements, freights);
        Map<OrderKey, CustomerOrder> orderByKey = new HashMap<>(orderById.size() * 2);
        orderById.values().forEach(o -> orderByKey.put(OrderKey.of(o), o));

        // 2. Split into matched / unmatched. Settlements use the full composite key; freight invoices carry no
        //    channel, so they match on order_id and inherit the order's channel for the composite key.
        Map<Boolean, List<MarketplaceSettlement>> sParts = settlements.parallelStream()
                .collect(Collectors.partitioningBy(s -> orderByKey.containsKey(OrderKey.of(s.getOrderId(), s.getChannel()))));
        Map<Boolean, List<FreightInvoice>> fParts = freights.parallelStream()
                .collect(Collectors.partitioningBy(f -> orderById.containsKey(norm(f.getOrderId()))));

        List<UnmatchedRecord> reviews = new ArrayList<>();
        reviews.addAll(sParts.get(false).parallelStream().map(s -> unmatchedSettlement(s, orderById)).toList());
        reviews.addAll(fParts.get(false).parallelStream().map(this::unmatchedFreight).toList());

        // 3. Aggregate per composite key (a settlement can arrive as several rows: sale, fee adjustment, ...).
        Map<OrderKey, MarketplaceSettlement> settlementByKey = sParts.get(true).parallelStream()
                .collect(Collectors.toConcurrentMap(
                        s -> OrderKey.of(s.getOrderId(), s.getChannel()), Function.identity(), this::mergeSettlements));
        Map<OrderKey, List<FreightInvoice>> freightByKey = fParts.get(true).parallelStream()
                .collect(Collectors.groupingByConcurrent(
                        f -> OrderKey.of(orderById.get(norm(f.getOrderId()))), Collectors.toList()));

        Set<OrderKey> keys = new HashSet<>(settlementByKey.keySet());
        keys.addAll(freightByKey.keySet());

        // 4. Compute in parallel. No DB / EntityManager access inside this stream.
        List<KeyOutcome> outcomes = keys.parallelStream()
                .map(k -> processKey(orderByKey.get(k), settlementByKey.get(k),
                        freightByKey.getOrDefault(k, List.of()), idx))
                .toList();

        List<Claim> candidates = new ArrayList<>();
        for (KeyOutcome o : outcomes) {
            candidates.addAll(o.claims());
            reviews.addAll(o.reviews());
        }

        // 5. Idempotency: drop claims that already exist for (order_id, type).
        List<Claim> fresh = dropExisting(candidates);
        int skipped = candidates.size() - fresh.size();

        // 6. Persist in flushed + detached chunks so the persistence context stays small.
        for (List<Claim> chunk : partition(fresh, FLUSH_CHUNK)) {
            claimRepository.saveAll(chunk);
            em.flush();
            chunk.forEach(em::detach);
        }
        for (List<UnmatchedRecord> chunk : partition(reviews, FLUSH_CHUNK)) {
            unmatchedRepository.saveAll(chunk);
            em.flush();
            chunk.forEach(em::detach);
        }

        Map<DiscrepancyType, BigDecimal> byType = fresh.stream().collect(Collectors.groupingBy(
                Claim::getDiscrepancyType, () -> new EnumMap<>(DiscrepancyType.class),
                Collectors.reducing(BigDecimal.ZERO, Claim::getDiscrepancyAmount, BigDecimal::add)));
        BigDecimal total = byType.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        log.info("Reconciled {} settlement + {} freight rows in {} ms: {} orders matched, {} to review, "
                        + "{} claims created ({} already existed), leakage INR {}",
                settlements.size(), freights.size(), ms, keys.size(), reviews.size(), fresh.size(), skipped, total);

        return new ReconciliationSummary(settlements.size(), freights.size(), keys.size(), reviews.size(),
                fresh.size(), skipped, total, byType, ms);
    }

    /**
     * Pure calculator (no DB access). Either {@code settlement} or {@code freight} may be null.
     * Uses the rate cards loaded at the start of the current batch.
     */
    public ComputationResult computeExpectedAndDiscrepancies(CustomerOrder order,
                                                             MarketplaceSettlement settlement,
                                                             FreightInvoice freight) {
        RateCardIndex idx = rateCardIndex;
        if (idx == null) {
            idx = refreshRateCards();
        }
        return compute(order, settlement, freight, idx);
    }

    // ------------------------------------------------------------------ calculator

    private ComputationResult compute(CustomerOrder order, MarketplaceSettlement settlement,
                                      FreightInvoice freight, RateCardIndex idx) {
        List<Claim> claims = new ArrayList<>(2);
        Map<SourceType, Reason> flags = new EnumMap<>(SourceType.class);
        BigDecimal expectedNet = null;
        BigDecimal actualNet = null;
        BigDecimal statutory = BigDecimal.ZERO;
        BigDecimal expectedFreight = null;

        // ---- marketplace settlement
        if (settlement != null && !isReturned(order)) {
            Optional<RateCard> card = idx.channelCard(order.getChannel());
            if (card.isEmpty()) {
                flags.put(SourceType.SETTLEMENT, Reason.NO_RATE_CARD);
            } else {
                BigDecimal orderValue = nz(order.getTotalValue());
                BigDecimal expectedFee = nz(card.get().getBaseFee());
                if (order.getPaymentMode() == PaymentMode.COD) {
                    expectedFee = expectedFee.add(orderValue.multiply(nz(card.get().getCodCommissionPct()))
                            .divide(HUNDRED, 2, RoundingMode.HALF_UP));
                }
                // Statutory deductions are legitimate, so they reduce the expected net payout one-for-one.
                statutory = nz(settlement.getGstTcs()).add(nz(settlement.getIncomeTaxTds()));
                expectedNet = orderValue.subtract(expectedFee).subtract(statutory).setScale(2, RoundingMode.HALF_UP);
                actualNet = nz(settlement.getNetPayout()).setScale(2, RoundingMode.HALF_UP);

                BigDecimal gap = expectedNet.subtract(actualNet);
                if (gap.compareTo(threshold) > 0) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("orderValue", orderValue);
                    ev.put("expectedFee", expectedFee);
                    ev.put("reportedMarketplaceFee", settlement.getMarketplaceFee());
                    ev.put("gstTcs", settlement.getGstTcs());
                    ev.put("incomeTaxTds", settlement.getIncomeTaxTds());
                    ev.put("expectedNetPayout", expectedNet);
                    ev.put("actualNetPayout", actualNet);
                    ev.put("settlementDate", settlement.getSettlementDate() == null ? null : settlement.getSettlementDate().toString());
                    claims.add(draftClaim(order.getOrderId(), order.getChannel(), DiscrepancyType.SHORT_PAID, gap, ev));
                }
            }
        }

        // ---- freight invoice
        if (freight != null) {
            Optional<BigDecimal> expected = expectedFreight(freight, idx);
            if (expected.isEmpty()) {
                flags.put(SourceType.FREIGHT_INVOICE, Reason.NO_RATE_CARD);
            } else {
                expectedFreight = expected.get().setScale(2, RoundingMode.HALF_UP);
                BigDecimal charged = nz(freight.getBaseCharge());
                BigDecimal over = charged.subtract(expectedFreight);
                if (over.compareTo(threshold) > 0) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("courier", freight.getCourierName());
                    ev.put("chargedWeightG", freight.getChargedWeight());
                    ev.put("appliedWeightSlab", freight.getAppliedWeightSlab());
                    ev.put("expectedCharge", expectedFreight);
                    ev.put("billedCharge", charged);
                    ev.put("invoiceDate", freight.getInvoiceDate() == null ? null : freight.getInvoiceDate().toString());
                    claims.add(draftClaim(order.getOrderId(), freight.getCourierName(),
                            DiscrepancyType.FREIGHT_OVERCHARGED, over, ev));
                }
            }
        }

        return new ComputationResult(expectedNet, actualNet, statutory, expectedFreight, claims, flags);
    }

    private Optional<BigDecimal> expectedFreight(FreightInvoice f, RateCardIndex idx) {
        int weight = f.getChargedWeight() == null ? 0 : f.getChargedWeight();
        Optional<RateCard> slab = idx.slabFor(f.getCourierName(), weight);
        if (slab.isPresent()) {
            return Optional.of(nz(slab.get().getBaseFee()));
        }
        // Heavier than every slab: top slab + extra_per_500g for every started 500 g above it.
        return idx.topSlab(f.getCourierName()).map(top -> {
            int extraUnits = (weight - top.getWeightSlabLimitG() + 499) / 500;
            return nz(top.getBaseFee()).add(nz(top.getExtraPer500g()).multiply(BigDecimal.valueOf(extraUnits)));
        });
    }

    // ------------------------------------------------------------------ per-key task (pure, thread-safe)

    private record KeyOutcome(List<Claim> claims, List<UnmatchedRecord> reviews) {}

    private KeyOutcome processKey(CustomerOrder order, MarketplaceSettlement settlement,
                                  List<FreightInvoice> freights, RateCardIndex idx) {
        List<Claim> claims = new ArrayList<>(2);
        List<UnmatchedRecord> reviews = new ArrayList<>(0);

        if (settlement != null) {
            absorb(compute(order, settlement, null, idx), order, claims, reviews);
        }
        for (FreightInvoice f : freights) {
            absorb(compute(order, null, f, idx), order, claims, reviews);
        }
        return new KeyOutcome(mergeClaims(claims), reviews);
    }

    private void absorb(ComputationResult r, CustomerOrder order, List<Claim> claims, List<UnmatchedRecord> reviews) {
        claims.addAll(r.draftClaims());
        r.reviewFlags().forEach((source, reason) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", order.getOrderId());
            payload.put("channel", order.getChannel());
            payload.put("note", "No rate card configured; expected amount could not be computed");
            reviews.add(review(source, reason, order.getOrderId(), order.getChannel(), payload));
        });
    }

    /** One claim per (order, type): amounts are summed, evidence combined into a JSON array. */
    private List<Claim> mergeClaims(List<Claim> claims) {
        if (claims.size() < 2) {
            return claims;
        }
        Map<DiscrepancyType, List<Claim>> byType = claims.stream().collect(Collectors.groupingBy(
                Claim::getDiscrepancyType, () -> new EnumMap<>(DiscrepancyType.class), Collectors.toList()));
        List<Claim> merged = new ArrayList<>(byType.size());
        for (List<Claim> group : byType.values()) {
            if (group.size() == 1) {
                merged.add(group.get(0));
                continue;
            }
            Claim first = group.get(0);
            first.setDiscrepancyAmount(group.stream().map(Claim::getDiscrepancyAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            first.setEvidenceJson(group.stream().map(Claim::getEvidenceJson)
                    .collect(Collectors.joining(",", "[", "]")));
            merged.add(first);
        }
        return merged;
    }

    // ------------------------------------------------------------------ data access (sequential, chunked)

    private RateCardIndex refreshRateCards() {
        RateCardIndex idx = RateCardIndex.of(rateCardRepository.findAll());
        this.rateCardIndex = idx;
        return idx;
    }

    private Map<String, CustomerOrder> loadOrders(List<MarketplaceSettlement> settlements, List<FreightInvoice> freights) {
        Set<String> ids = new HashSet<>();
        settlements.forEach(s -> ids.add(norm(s.getOrderId())));
        freights.forEach(f -> ids.add(norm(f.getOrderId())));
        ids.remove("");

        Map<String, CustomerOrder> result = new HashMap<>(ids.size() * 2);
        for (List<String> chunk : partition(new ArrayList<>(ids), DB_CHUNK)) {
            for (CustomerOrder o : orderRepository.findByOrderIdIn(chunk)) {
                result.put(norm(o.getOrderId()), o);
                em.detach(o);   // read-only use; keeps the persistence context (and dirty-checking) small
            }
        }
        return result;
    }

    private List<Claim> dropExisting(List<Claim> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<String> orderIds = candidates.stream().map(Claim::getOrderId).distinct().toList();
        Set<String> existing = new HashSet<>();
        for (List<String> chunk : partition(orderIds, DB_CHUNK)) {
            for (Claim c : claimRepository.findByOrderIdIn(chunk)) {
                existing.add(c.getOrderId() + '|' + c.getDiscrepancyType());
                em.detach(c);
            }
        }
        return candidates.stream()
                .filter(c -> !existing.contains(c.getOrderId() + '|' + c.getDiscrepancyType()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private MarketplaceSettlement mergeSettlements(MarketplaceSettlement a, MarketplaceSettlement b) {
        return MarketplaceSettlement.builder()
                .orderId(a.getOrderId())
                .channel(a.getChannel())
                .itemPrice(nz(a.getItemPrice()).add(nz(b.getItemPrice())))
                .marketplaceFee(nz(a.getMarketplaceFee()).add(nz(b.getMarketplaceFee())))
                .gstTcs(nz(a.getGstTcs()).add(nz(b.getGstTcs())))
                .incomeTaxTds(nz(a.getIncomeTaxTds()).add(nz(b.getIncomeTaxTds())))
                .netPayout(nz(a.getNetPayout()).add(nz(b.getNetPayout())))
                .settlementDate(a.getSettlementDate() == null ? b.getSettlementDate()
                        : b.getSettlementDate() == null ? a.getSettlementDate()
                        : a.getSettlementDate().isAfter(b.getSettlementDate()) ? a.getSettlementDate() : b.getSettlementDate())
                .build();
    }

    private UnmatchedRecord unmatchedSettlement(MarketplaceSettlement s, Map<String, CustomerOrder> orderById) {
        // Order exists but under another channel => composite-key mismatch, otherwise the order is unknown.
        Reason reason = orderById.containsKey(norm(s.getOrderId())) ? Reason.CHANNEL_MISMATCH : Reason.ORDER_NOT_FOUND;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", s.getOrderId());
        p.put("channel", s.getChannel());
        p.put("itemPrice", s.getItemPrice());
        p.put("netPayout", s.getNetPayout());
        p.put("settlementDate", s.getSettlementDate() == null ? null : s.getSettlementDate().toString());
        return review(SourceType.SETTLEMENT, reason, s.getOrderId(), s.getChannel(), p);
    }

    private UnmatchedRecord unmatchedFreight(FreightInvoice f) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", f.getOrderId());
        p.put("courier", f.getCourierName());
        p.put("chargedWeightG", f.getChargedWeight());
        p.put("baseCharge", f.getBaseCharge());
        p.put("invoiceDate", f.getInvoiceDate() == null ? null : f.getInvoiceDate().toString());
        return review(SourceType.FREIGHT_INVOICE, Reason.ORDER_NOT_FOUND, f.getOrderId(), null, p);
    }

    private UnmatchedRecord review(SourceType source, Reason reason, String orderId, String channel, Map<String, Object> payload) {
        return UnmatchedRecord.builder()
                .sourceType(source).reason(reason).orderId(orderId).channel(channel)
                .payloadJson(toJson(payload)).build();
    }

    private Claim draftClaim(String orderId, String partner, DiscrepancyType type, BigDecimal amount, Map<String, Object> evidence) {
        return Claim.builder()
                .orderId(orderId).partnerName(partner).discrepancyType(type)
                .discrepancyAmount(amount.setScale(2, RoundingMode.HALF_UP))
                .status(ClaimStatus.OPEN)           // OPEN == draft, awaiting submission to the partner
                .evidenceJson(toJson(evidence)).build();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise JSON payload", e);
        }
    }

    private static boolean isReturned(CustomerOrder o) {
        return o.getDeliveryStatus() != null && o.getDeliveryStatus().toLowerCase(Locale.ROOT).contains("return");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    private static String normName(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>((list.size() + size - 1) / size);
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return out;
    }

    // ------------------------------------------------------------------ keys and rate-card index

    /** Composite lookup key: order_id (trimmed) + channel (trimmed, case-insensitive). */
    private record OrderKey(String orderId, String channel) {
        static OrderKey of(String orderId, String channel) {
            return new OrderKey(norm(orderId), normName(channel));
        }
        static OrderKey of(CustomerOrder o) {
            return of(o.getOrderId(), o.getChannel());
        }
    }

    /** Immutable per-batch snapshot of rate_cards; slabs sorted ascending per channel/courier. */
    private record RateCardIndex(Map<String, List<RateCard>> byName) {

        static RateCardIndex of(Collection<RateCard> cards) {
            Map<String, List<RateCard>> m = cards.stream()
                    .collect(Collectors.groupingBy(c -> normName(c.getChannelOrCourierName())));
            m.values().forEach(l -> l.sort(Comparator.comparingInt(RateCard::getWeightSlabLimitG)));
            return new RateCardIndex(m);
        }

        Optional<RateCard> channelCard(String name) {
            List<RateCard> l = byName.get(normName(name));
            return l == null || l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
        }

        Optional<RateCard> slabFor(String name, int weightG) {
            List<RateCard> l = byName.get(normName(name));
            return l == null ? Optional.empty()
                    : l.stream().filter(c -> c.getWeightSlabLimitG() >= weightG).findFirst();
        }

        Optional<RateCard> topSlab(String name) {
            List<RateCard> l = byName.get(normName(name));
            return l == null || l.isEmpty() ? Optional.empty() : Optional.of(l.get(l.size() - 1));
        }
    }
}
