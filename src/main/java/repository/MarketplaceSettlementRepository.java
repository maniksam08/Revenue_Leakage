package repository;

import entity.MarketplaceSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface MarketplaceSettlementRepository extends JpaRepository<MarketplaceSettlement, Long> {

    List<MarketplaceSettlement> findByOrderId(String orderId);

    List<MarketplaceSettlement> findByOrderIdIn(Collection<String> orderIds);

    List<MarketplaceSettlement> findByChannelAndSettlementDateBetween(String channel, LocalDate from, LocalDate to);

    @Query("select coalesce(sum(s.netPayout), 0) from MarketplaceSettlement s where s.orderId = :orderId")
    BigDecimal sumNetPayoutByOrderId(@Param("orderId") String orderId);

    @Query("""
           select coalesce(sum(s.netPayout), 0) from MarketplaceSettlement s
           where s.channel = :channel and s.settlementDate between :from and :to
           """)
    BigDecimal sumNetPayoutByChannelAndPeriod(@Param("channel") String channel,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);
}
