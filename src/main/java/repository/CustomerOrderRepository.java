package repository;

import entity.CustomerOrder;
import enums.PaymentMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByOrderId(String orderId);

    boolean existsByOrderId(String orderId);

    List<CustomerOrder> findByOrderIdIn(Collection<String> orderIds);

    Page<CustomerOrder> findByChannel(String channel, Pageable pageable);

    List<CustomerOrder> findByChannelAndDeliveryStatus(String channel, String deliveryStatus);

    /** Orders where less money arrived than expected. */
    @Query("""
           select o from CustomerOrder o
           where o.expectedAmount is not null
             and coalesce(o.receivedAmount, 0) < o.expectedAmount
             and (:channel is null or o.channel = :channel)
           """)
    Page<CustomerOrder> findShortPaid(@Param("channel") String channel, Pageable pageable);

    /** Delivered COD orders where no cash has been remitted. */
    @Query("""
           select o from CustomerOrder o
           where o.paymentMode = :mode
             and o.deliveryStatus = :status
             and coalesce(o.receivedAmount, 0) = 0
           """)
    List<CustomerOrder> findUnremitted(@Param("mode") PaymentMode mode, @Param("status") String status);

    /**
     * Keyset-paged scan for delivered COD orders whose remittance is overdue (older than the grace cutoff) and
     * short by more than the threshold, and that have no UNPAID_COD claim yet. Expected = expected_amount, falling
     * back to total_value; this must match CodGap.gap().
     */
    @Query("""
           select o from CustomerOrder o
           where o.paymentMode = com.reconciler.enums.PaymentMode.COD
             and lower(o.deliveryStatus) = 'delivered'
             and o.createdAt < :cutoff
             and o.id > :afterId
             and coalesce(o.receivedAmount, 0) < coalesce(o.expectedAmount, o.totalValue) - :threshold
             and not exists (select 1 from Claim c where c.orderId = o.orderId
                             and c.discrepancyType = com.reconciler.enums.DiscrepancyType.UNPAID_COD)
           order by o.id asc
           """)
    List<CustomerOrder> findUnremittedCodBatch(@Param("cutoff") java.time.Instant cutoff,
                                               @Param("afterId") long afterId,
                                               @Param("threshold") java.math.BigDecimal threshold,
                                               org.springframework.data.domain.Pageable pageable);
}
