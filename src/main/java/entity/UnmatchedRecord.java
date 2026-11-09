package entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Manual-review queue for rows the engine could not safely reconcile. */
@Entity
@Table(name = "unmatched_records",
        indexes = {
                @Index(name = "idx_unmatched_order_id", columnList = "order_id"),
                @Index(name = "idx_unmatched_status_reason", columnList = "status, reason")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnmatchedRecord {

    public enum SourceType { SETTLEMENT, FREIGHT_INVOICE, ORDER }
    public enum Reason { ORDER_NOT_FOUND, CHANNEL_MISMATCH, NO_RATE_CARD, NO_COURIER_FOR_COD }
    public enum ReviewStatus { PENDING, RESOLVED, DISMISSED }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "unmatched_seq")
    @SequenceGenerator(name = "unmatched_seq", sequenceName = "unmatched_seq", allocationSize = 50)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 24)
    private SourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 24)
    private Reason reason;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "channel", length = 64)
    private String channel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ReviewStatus status = ReviewStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
