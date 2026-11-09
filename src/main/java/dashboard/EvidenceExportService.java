package dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import dashboard.ClaimDtos.ExportFormat;
import dashboard.ClaimDtos.ExportedFile;
import dashboard.EvidenceBundle.ClaimInfo;
import dashboard.EvidenceBundle.FreightInfo;
import dashboard.EvidenceBundle.Meta;
import dashboard.EvidenceBundle.OrderInfo;
import dashboard.EvidenceBundle.SettlementInfo;
import entity.Claim;
import entity.CustomerOrder;
import entity.FreightInvoice;
import entity.MarketplaceSettlement;
import enums.DiscrepancyType;
import repository.ClaimRepository;
import repository.CustomerOrderRepository;
import repository.FreightInvoiceRepository;
import repository.MarketplaceSettlementRepository;
import web.ClaimNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Read-only: assembling an export never changes the claim. */
@Service
@RequiredArgsConstructor
public class EvidenceExportService {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ClaimRepository claimRepository;
    private final CustomerOrderRepository orderRepository;
    private final MarketplaceSettlementRepository settlementRepository;
    private final FreightInvoiceRepository freightRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ExportedFile export(long claimId, ExportFormat format) {
        Claim claim = claimRepository.findById(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));
        ObjectMapper exact = exactMapper();
        EvidenceBundle bundle = assemble(claim, exact);
        try {
            return switch (format) {
                case JSON -> new ExportedFile(
                        objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(bundle),
                        MediaType.APPLICATION_JSON, filename(claimId, "json"));
                case CSV -> new ExportedFile(
                        EvidenceCsvWriter.write(exact.valueToTree(bundle)), CSV, filename(claimId, "csv"));
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise evidence bundle for claim " + claimId, e);
        }
    }

    private EvidenceBundle assemble(Claim claim, ObjectMapper exact) {
        List<String> notes = new ArrayList<>();
        notes.add("All amounts are in INR.");

        CustomerOrder order = orderRepository.findByOrderId(claim.getOrderId()).orElse(null);
        if (order == null) notes.add("The order record was not found; order context omitted.");

        DiscrepancyType type = claim.getDiscrepancyType();
        String partner = claim.getPartnerName();

        List<SettlementInfo> settlements = List.of();
        if (type == DiscrepancyType.SHORT_PAID || type == DiscrepancyType.UNPAID_COD) {
            settlements = settlementRepository.findByOrderId(claim.getOrderId()).stream()
                    .filter(s -> type == DiscrepancyType.UNPAID_COD || partner.equalsIgnoreCase(s.getChannel()))
                    .map(EvidenceExportService::toInfo).toList();
        }
        List<FreightInfo> freight = List.of();
        if (type != DiscrepancyType.SHORT_PAID) {
            freight = freightRepository.findByOrderId(claim.getOrderId()).stream()
                    .filter(f -> type != DiscrepancyType.FREIGHT_OVERCHARGED || partner.equalsIgnoreCase(f.getCourierName()))
                    .map(EvidenceExportService::toInfo).toList();
        }
        if (type == DiscrepancyType.SHORT_PAID) {
            notes.add("GST TCS and income-tax TDS are statutory deductions and are excluded from the claimed amount; "
                    + "the claim covers only the shortfall after adding them back.");
        }

        return new EvidenceBundle(
                new Meta("1.0", Instant.now(), "INR"),
                new ClaimInfo(claim.getId(), claim.getOrderId(), partner, type.name(), claim.getDiscrepancyAmount(),
                        claim.getStatus().name(), claim.getCreatedAt()),
                order == null ? null : toInfo(order),
                settlements, freight, parse(claim.getEvidenceJson(), notes, exact), notes);
    }

    private JsonNode parse(String json, List<String> notes, ObjectMapper exact) {
        if (json == null || json.isBlank()) return null;
        try {
            return exact.readTree(json);
        } catch (JsonProcessingException e) {
            notes.add("Stored calculation details could not be parsed and were omitted.");
            return null;
        }
    }

    /**
     * Money must keep its written scale: Jackson's tree model strips trailing zeros by default (35.00 -> 35) and
     * parses decimals as doubles. This copy keeps 2-decimal amounts exact in both JSON and CSV output.
     */
    private ObjectMapper exactMapper() {
        return objectMapper.copy()
                .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    private static OrderInfo toInfo(CustomerOrder o) {
        return new OrderInfo(o.getOrderId(), o.getChannel(), o.getTotalValue(),
                o.getPaymentMode() == null ? null : o.getPaymentMode().name(), o.getDeliveryStatus(),
                o.getExpectedAmount(), o.getReceivedAmount(), o.getCreatedAt());
    }

    // raw_payload is intentionally NOT exported: it can carry buyer details and marketplace-internal fields.
    private static SettlementInfo toInfo(MarketplaceSettlement s) {
        return new SettlementInfo(s.getChannel(), s.getItemPrice(), s.getMarketplaceFee(), s.getGstTcs(),
                s.getIncomeTaxTds(), s.getNetPayout(), s.getSettlementDate());
    }

    private static FreightInfo toInfo(FreightInvoice f) {
        return new FreightInfo(f.getCourierName(), f.getChargedWeight(), f.getAppliedWeightSlab(),
                f.getBaseCharge(), f.getInvoiceDate());
    }

    private static String filename(long claimId, String ext) {
        return "claim-" + claimId + "-evidence." + ext;
    }
}
