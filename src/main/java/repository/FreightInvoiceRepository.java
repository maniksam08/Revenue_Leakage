package repository;

import entity.FreightInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface FreightInvoiceRepository extends JpaRepository<FreightInvoice, Long> {

    List<FreightInvoice> findByOrderId(String orderId);

    List<FreightInvoice> findByCourierNameAndInvoiceDateBetween(String courierName, LocalDate from, LocalDate to);

    List<FreightInvoice> findByOverchargeAmountGreaterThan(BigDecimal threshold);

    @Query("""
           select coalesce(sum(f.overchargeAmount), 0) from FreightInvoice f
           where f.courierName = :courier and f.invoiceDate between :from and :to
           """)
    BigDecimal sumOverchargeByCourierAndPeriod(@Param("courier") String courier,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    List<FreightInvoice> findByOrderIdIn(java.util.Collection<String> orderIds);
}
