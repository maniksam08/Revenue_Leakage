package entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "freight_invoices",
       indexes = {
           @Index(name = "idx_freight_order_id", columnList = "order_id"),
           @Index(name = "idx_freight_courier", columnList = "courier_name"),
           @Index(name = "idx_freight_courier_date", columnList = "courier_name, invoice_date")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreightInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "courier_name", nullable = false, length = 64)
    private String courierName;

    /** Weight the courier billed, in grams. */
    @Column(name = "charged_weight")
    private Integer chargedWeight;

    /** Slab applied by the courier, e.g. 500, 1000, 1500 (grams). */
    @Column(name = "applied_weight_slab")
    private Integer appliedWeightSlab;

    @Column(name = "base_charge", precision = 14, scale = 2)
    private BigDecimal baseCharge;

    /** Amount billed above the contracted rate-card price (filled by the reconciler). */
    @Column(name = "overcharge_amount", precision = 14, scale = 2)
    private BigDecimal overchargeAmount;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;
}
