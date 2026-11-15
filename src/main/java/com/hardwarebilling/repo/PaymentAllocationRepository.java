package com.hardwarebilling.repo;

import com.hardwarebilling.domain.Enums.RecordStatus;
import com.hardwarebilling.domain.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    @Query("select coalesce(sum(a.amount), 0) from PaymentAllocation a where a.invoice.id = :id and a.status = :st")
    BigDecimal sumByInvoice(@Param("id") Long invoiceId, @Param("st") RecordStatus status);

    @Query("select coalesce(sum(a.amount), 0) from PaymentAllocation a where a.payment.id = :id and a.status = :st")
    BigDecimal sumByPayment(@Param("id") Long paymentId, @Param("st") RecordStatus status);

    default BigDecimal activeSumByInvoice(Long invoiceId) { return sumByInvoice(invoiceId, RecordStatus.ACTIVE); }
    default BigDecimal activeSumByPayment(Long paymentId) { return sumByPayment(paymentId, RecordStatus.ACTIVE); }

    List<PaymentAllocation> findByInvoiceIdOrderByIdAsc(Long invoiceId);
    List<PaymentAllocation> findByPaymentIdOrderByIdAsc(Long paymentId);
    List<PaymentAllocation> findByPaymentIdAndStatus(Long paymentId, RecordStatus status);
    List<PaymentAllocation> findByInvoiceIdAndStatus(Long invoiceId, RecordStatus status);
}
