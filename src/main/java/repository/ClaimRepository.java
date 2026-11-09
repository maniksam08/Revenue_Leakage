package repository;

import entity.Claim;
import enums.ClaimStatus;
import enums.DiscrepancyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

    List<Claim> findByOrderId(String orderId);

    List<Claim> findByOrderIdIn(java.util.Collection<String> orderIds);

    /** Idempotency check so re-running the reconciler doesn't raise duplicate claims. */
    boolean existsByOrderIdAndDiscrepancyType(String orderId, DiscrepancyType type);

    Page<Claim> findByStatus(ClaimStatus status, Pageable pageable);

    List<Claim> findByPartnerNameAndStatus(String partnerName, ClaimStatus status);

    @Query("""
           select coalesce(sum(c.discrepancyAmount), 0) from Claim c
           where c.status = :status and (:partner is null or c.partnerName = :partner)
           """)
    BigDecimal sumAmountByStatus(@Param("status") ClaimStatus status, @Param("partner") String partner);

    @Query("""
           select c.discrepancyType as type, coalesce(sum(c.discrepancyAmount), 0) as total
           from Claim c where c.status = :status group by c.discrepancyType
           """)
    List<LeakageSummary> summarizeByType(@Param("status") ClaimStatus status);

    /**
     * Unrecovered claims of the given types, bucketed by ORDER age in the database. cut30/cut60 are start-of-day
     * instants from AgeBucket.cutoffs(), so SQL and Java classify identically. Inner join: claims always
     * originate from a stored order.
     */
    @Query("""
           select c.partnerName as partnerName, c.discrepancyType as discrepancyType,
                  count(case when o.createdAt >= :cut30 then c.id end) as count0To30,
                  coalesce(sum(case when o.createdAt >= :cut30 then c.discrepancyAmount end), 0) as amount0To30,
                  count(case when o.createdAt < :cut30 and o.createdAt >= :cut60 then c.id end) as count31To60,
                  coalesce(sum(case when o.createdAt < :cut30 and o.createdAt >= :cut60 then c.discrepancyAmount end), 0) as amount31To60,
                  count(case when o.createdAt < :cut60 then c.id end) as count61Plus,
                  coalesce(sum(case when o.createdAt < :cut60 then c.discrepancyAmount end), 0) as amount61Plus
           from Claim c join CustomerOrder o on o.orderId = c.orderId
           where c.discrepancyType in :types and c.status in :statuses
           group by c.partnerName, c.discrepancyType
           """)
    List<RiskAggregate> aggregateRisk(@Param("types") java.util.Collection<DiscrepancyType> types,
                                      @Param("statuses") java.util.Collection<ClaimStatus> statuses,
                                      @Param("cut30") java.time.Instant cut30,
                                      @Param("cut60") java.time.Instant cut60);

    interface RiskAggregate {
        String getPartnerName();
        DiscrepancyType getDiscrepancyType();
        Long getCount0To30();
        BigDecimal getAmount0To30();
        Long getCount31To60();
        BigDecimal getAmount31To60();
        Long getCount61Plus();
        BigDecimal getAmount61Plus();
    }

    interface LeakageSummary {
        DiscrepancyType getType();
        BigDecimal getTotal();
    }
}
