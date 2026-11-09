package entity;

import enums.PaymentMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** Named CustomerOrder because ORDER is a reserved word in SQL/JPQL. Table is still "orders". */
@Entity
@Table(name = "orders",
       uniqueConstraints = @UniqueConstraint(name = "uk_orders_order_id", columnNames = "order_id"),
       indexes = {
           @Index(name = "idx_orders_order_id", columnList = "order_id"),
           @Index(name = "idx_orders_channel", columnList = "channel"),
           @Index(name = "idx_orders_delivery_status", columnList = "delivery_status")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    /** Website, Marketplace 1, Marketplace 2, ... */
    @Column(name = "channel", nullable = false, length = 64)
    private String channel;

    @Column(name = "total_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 16)
    private PaymentMode paymentMode;

    /** Delivered, Returned to warehouse, In transit, ... (kept as string; values come from courier feeds) */
    @Column(name = "delivery_status", length = 64)
    private String deliveryStatus;

    @Column(name = "expected_amount", precision = 14, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "received_amount", precision = 14, scale = 2)
    private BigDecimal receivedAmount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
