package com.hardwarebilling.repo;

import com.hardwarebilling.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.payment.id = :id")
    BigDecimal sumByPayment(@Param("id") Long paymentId);

    List<Refund> findByPaymentIdOrderByIdAsc(Long paymentId);
    List<Refund> findByCustomerIdOrderByRefundDateAscIdAsc(Long customerId);
}
