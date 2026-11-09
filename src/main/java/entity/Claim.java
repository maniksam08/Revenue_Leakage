package entity;

import enums.ClaimStatus;
import enums.DiscrepancyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "claims",
        uniqueConstraints = @UniqueConstraint(name = "uk_claims_order_type",
                columnNames = {"order_id", "discrepancy_type"}),
        indexes = {
                @Index(name = "idx_claims_order_id", columnList = "order_id"),
                @Index(name = "idx_claims_partner_status", columnList = "partner_name, status"),
                @Index(name = "idx_claims_status", columnList = "status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "claims_seq")
    @SequenceGenerator(name = "claims_seq", sequenceName = "claims_seq", allocationSize = 50)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    /** Marketplace or courier the claim is raised against. */
    @Column(name = "partner_name", nullable = false, length = 64)
    private String partnerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 32)
    private DiscrepancyType discrepancyType;

    @Column(name = "discrepancy_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discrepancyAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ClaimStatus status = ClaimStatus.OPEN;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private String evidenceJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
