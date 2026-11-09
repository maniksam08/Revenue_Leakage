package entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "rate_cards",
       uniqueConstraints = @UniqueConstraint(name = "uk_rate_cards_name_slab",
                                             columnNames = {"channel_or_courier_name", "weight_slab_limit_g"}),
       indexes = @Index(name = "idx_rate_cards_name", columnList = "channel_or_courier_name"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_or_courier_name", nullable = false, length = 64)
    private String channelOrCourierName;

    /** Upper bound (grams) this slab covers. */
    @Column(name = "weight_slab_limit_g", nullable = false)
    private Integer weightSlabLimitG;

    @Column(name = "base_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseFee;

    /** Added for every extra 500g beyond the slab limit. */
    @Column(name = "extra_per_500g", precision = 14, scale = 2)
    private BigDecimal extraPer500g;

    /** COD commission as a percentage, e.g. 1.75 means 1.75%. */
    @Column(name = "cod_commission_pct", precision = 5, scale = 2)
    private BigDecimal codCommissionPct;
}
