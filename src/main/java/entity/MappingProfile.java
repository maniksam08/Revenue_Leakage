package entity;

import enums.ReportType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Approved column mapping for one partner + report type. Every approval is a new version; one version is active. */
@Entity
@Table(name = "mapping_profiles",
       uniqueConstraints = @UniqueConstraint(name = "uk_mapping_profile_version",
                                             columnNames = {"partner_id", "report_type", "version"}),
       indexes = @Index(name = "idx_mapping_profile_active", columnList = "partner_id, report_type, active"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // low-volume table; batching not needed
    private Long id;

    @Column(name = "partner_id", nullable = false, length = 64)
    private String partnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 16)
    private ReportType reportType;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** canonical field name -> source header text, e.g. {"order_id": "Ord_ID", "net_payout": "Net Payout (INR)"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_mappings", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> columnMappings;

    @Column(name = "preamble_rows", nullable = false)
    private int preambleRows;

    @Column(name = "approved_by", length = 128)
    private String approvedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
