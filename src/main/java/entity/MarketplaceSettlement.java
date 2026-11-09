package entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "marketplace_settlements",
       indexes = {
           @Index(name = "idx_settlements_order_id", columnList = "order_id"),
           @Index(name = "idx_settlements_channel", columnList = "channel"),
           @Index(name = "idx_settlements_channel_date", columnList = "channel, settlement_date")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketplaceSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Plain string reference (no FK): settlements can arrive before the order is ingested. */
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "channel", nullable = false, length = 64)
    private String channel;

    @Column(name = "item_price", precision = 14, scale = 2)
    private BigDecimal itemPrice;

    @Column(name = "marketplace_fee", precision = 14, scale = 2)
    private BigDecimal marketplaceFee;

    @Column(name = "gst_tcs", precision = 14, scale = 2)
    private BigDecimal gstTcs;

    @Column(name = "income_tax_tds", precision = 14, scale = 2)
    private BigDecimal incomeTaxTds;

    @Column(name = "net_payout", precision = 14, scale = 2)
    private BigDecimal netPayout;

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;
}
