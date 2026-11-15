package com.hardwarebilling.service;

import com.hardwarebilling.config.SecurityUtil;
import com.hardwarebilling.domain.*;
import com.hardwarebilling.domain.Enums.DocStatus;
import com.hardwarebilling.domain.Enums.PaymentMethod;
import com.hardwarebilling.domain.Enums.RecordStatus;
import com.hardwarebilling.repo.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Receipts, allocations, advances, refunds and reversals.
 *
 * Money rules:
 *  - invoice balance          = invoice total - ACTIVE allocations
 *  - receipt available credit = receipt amount - ACTIVE allocations - refunds   (0 once reversed)
 *  - applying an advance is an allocation of an existing receipt, never a new receipt
 *
 * Concurrency: every operation locks the receipt row first, then invoice rows in ascending id
 * order (SELECT ... FOR UPDATE), then re-reads the sums. Two requests spending the same money
 * therefore serialise, and the second sees the first one's allocation.
 */
@Service
public class PaymentService {

    public record AllocationRequest(Long invoiceId, BigDecimal amount) {}

    public record ReceiptRequest(Long customerId, LocalDate paymentDate, BigDecimal amount, PaymentMethod method,
                                 Long bankAccountId, String reference, String notes, String idempotencyKey,
                                 List<AllocationRequest> allocations) {}

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final RefundRepository refunds;
    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final BankAccountRepository banks;
    private final CompanyService company;
    private final NumberingService numbering;
    private final AuditService audit;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    public PaymentService(PaymentRepository payments, PaymentAllocationRepository allocations, RefundRepository refunds,
                          InvoiceRepository invoices, CustomerRepository customers, BankAccountRepository banks,
                          CompanyService company, NumberingService numbering, AuditService audit) {
        this.payments = payments;
        this.allocations = allocations;
        this.refunds = refunds;
        this.invoices = invoices;
        this.customers = customers;
        this.banks = banks;
        this.company = company;
        this.numbering = numbering;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Payment get(Long id) {
        return payments.findById(id).orElseThrow(() -> new BusinessException("Receipt not found"));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Payment> findByKey(String key) {
        return key == null ? java.util.Optional.empty() : payments.findByIdempotencyKey(key);
    }

    @Transactional(readOnly = true)
    public BigDecimal availableCredit(Payment p) {
        if (p.getStatus() != RecordStatus.ACTIVE) return BigDecimal.ZERO;
        return p.getAmount().subtract(allocations.activeSumByPayment(p.getId())).subtract(refunds.sumByPayment(p.getId()));
    }

    // ------------------------------------------------------------------ create receipt (+ optional allocations)

    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public Payment createReceipt(ReceiptRequest r) {
        if (r.idempotencyKey() != null && !r.idempotencyKey().isBlank()) {
            var existing = payments.findByIdempotencyKey(r.idempotencyKey());
            if (existing.isPresent()) return existing.get();      // duplicate submission: return the first receipt
        }
        CompanyProfile co = company.get();
        int scale = Money.scaleFor(co.getCurrency());
        BigDecimal amount = Money.exact(r.amount(), scale);
        if (amount.signum() <= 0) throw new BusinessException("Amount must be greater than zero");
        if (r.paymentDate() == null) throw new BusinessException("Payment date is required");
        if (r.method() == null) throw new BusinessException("Payment method is required");
        Customer customer = customers.findById(r.customerId()).orElseThrow(() -> new BusinessException("Customer not found"));

        List<AllocationRequest> allocReqs = new ArrayList<>();
        BigDecimal allocTotal = BigDecimal.ZERO;
        if (r.allocations() != null) {
            for (AllocationRequest a : r.allocations()) {
                if (a == null || a.amount() == null || a.amount().signum() == 0) continue;
                BigDecimal amt = Money.exact(a.amount(), scale);
                if (amt.signum() < 0) throw new BusinessException("Allocation amounts cannot be negative");
                allocReqs.add(new AllocationRequest(a.invoiceId(), amt));
                allocTotal = allocTotal.add(amt);
            }
        }
        if (allocTotal.compareTo(amount) > 0) throw new BusinessException("Allocated amount exceeds the amount received");

        Payment p = new Payment();
        p.setReceiptNo(co.getReceiptPrefix() + numbering.next("RECEIPT"));
        p.setCustomer(customer);
        p.setPaymentDate(r.paymentDate());
        p.setAmount(amount);
        p.setMethod(r.method());
        if (r.bankAccountId() != null) {
            BankAccount b = banks.findById(r.bankAccountId()).orElseThrow(() -> new BusinessException("Bank account not found"));
            p.setBankAccountId(b.getId());
            p.setBankLabel(b.getBankName() + (b.getAccountNumber() != null ? " (" + b.getAccountNumber() + ")" : ""));
        }
        p.setReference(ItemService.blankToNull(r.reference()));
        p.setNotes(ItemService.blankToNull(r.notes()));
        p.setIdempotencyKey(r.idempotencyKey() == null || r.idempotencyKey().isBlank() ? null : r.idempotencyKey());
        p.setCreatedBy(SecurityUtil.username());
        payments.saveAndFlush(p);   // the unique idempotency key rejects a concurrent duplicate here

        allocReqs.sort(Comparator.comparing(AllocationRequest::invoiceId));   // consistent lock order
        for (AllocationRequest a : allocReqs) {
            doAllocate(p, a.invoiceId(), a.amount(), r.paymentDate());
        }
        audit.log("RECEIPT_CREATE", "Payment", p.getId(), p.getReceiptNo() + " " + amount.toPlainString() + " " + co.getCurrency());
        return p;
    }

    // ------------------------------------------------------------------ apply existing credit / allocate

    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public PaymentAllocation allocate(Long paymentId, Long invoiceId, BigDecimal amount, LocalDate date) {
        Payment p = payments.lockById(paymentId).orElseThrow(() -> new BusinessException("Receipt not found"));
        int scale = Money.scaleFor(company.get().getCurrency());
        BigDecimal amt = Money.exact(amount, scale);
        if (amt.signum() <= 0) throw new BusinessException("Amount must be greater than zero");
        PaymentAllocation a = doAllocate(p, invoiceId, amt, date == null ? company.today() : date);
        audit.log("ALLOCATE", "Payment", p.getId(), p.getReceiptNo() + " -> invoice " + a.getInvoice().getInvoiceNumber() + " " + amt.toPlainString());
        return a;
    }

    /** Caller must already hold the payment's row lock (or have just inserted it). */
    private PaymentAllocation doAllocate(Payment p, Long invoiceId, BigDecimal amt, LocalDate date) {
        if (invoiceId == null) throw new BusinessException("Select an invoice");
        if (p.getStatus() != RecordStatus.ACTIVE) throw new BusinessException("This receipt has been reversed");
        Invoice inv = invoices.lockById(invoiceId).orElseThrow(() -> new BusinessException("Invoice not found"));
        if (!inv.getCustomer().getId().equals(p.getCustomer().getId())) {
            throw new BusinessException("A payment cannot be allocated to another customer's invoice");
        }
        if (inv.getDocStatus() != DocStatus.ISSUED) throw new BusinessException("Payments can only be allocated to issued invoices");
        BigDecimal balance = inv.getGrandTotal().subtract(allocations.activeSumByInvoice(inv.getId()));
        if (amt.compareTo(balance) > 0) {
            throw new BusinessException("Allocation " + amt.toPlainString() + " exceeds the invoice balance " + balance.toPlainString());
        }
        BigDecimal credit = p.getAmount().subtract(allocations.activeSumByPayment(p.getId())).subtract(refunds.sumByPayment(p.getId()));
        if (amt.compareTo(credit) > 0) {
            throw new BusinessException("Allocation " + amt.toPlainString() + " exceeds the available credit " + credit.toPlainString() + " on " + p.getReceiptNo());
        }
        PaymentAllocation a = new PaymentAllocation();
        a.setPayment(p);
        a.setInvoice(inv);
        a.setAmount(amt);
        a.setAllocationDate(date);
        a.setCreatedBy(SecurityUtil.username());
        return allocations.saveAndFlush(a);
    }

    // ------------------------------------------------------------------ refunds & reversals (admin only)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Refund refund(Long paymentId, BigDecimal amount, LocalDate date, PaymentMethod method, String reference, String notes) {
        Payment p = payments.lockById(paymentId).orElseThrow(() -> new BusinessException("Receipt not found"));
        if (p.getStatus() != RecordStatus.ACTIVE) throw new BusinessException("This receipt has been reversed");
        BigDecimal amt = Money.exact(amount, Money.scaleFor(company.get().getCurrency()));
        if (amt.signum() <= 0) throw new BusinessException("Amount must be greater than zero");
        BigDecimal credit = p.getAmount().subtract(allocations.activeSumByPayment(p.getId())).subtract(refunds.sumByPayment(p.getId()));
        if (amt.compareTo(credit) > 0) throw new BusinessException("Refund exceeds the available credit " + credit.toPlainString());
        Refund r = new Refund();
        r.setPayment(p);
        r.setCustomer(p.getCustomer());
        r.setAmount(amt);
        r.setRefundDate(date == null ? company.today() : date);
        r.setMethod(method == null ? PaymentMethod.OTHER : method);
        r.setReference(ItemService.blankToNull(reference));
        r.setNotes(ItemService.blankToNull(notes));
        r.setCreatedBy(SecurityUtil.username());
        refunds.save(r);
        audit.log("REFUND", "Payment", p.getId(), p.getReceiptNo() + " refund " + amt.toPlainString());
        return r;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void reverseAllocation(Long allocationId, String reason) {
        requireReason(reason);
        PaymentAllocation a = allocations.findById(allocationId).orElseThrow(() -> new BusinessException("Allocation not found"));
        payments.lockById(a.getPayment().getId());
        invoices.lockById(a.getInvoice().getId());
        em.refresh(a);   // state may be stale if another request reversed it while we waited for the locks
        if (a.getStatus() != RecordStatus.ACTIVE) throw new BusinessException("Allocation is already reversed");
        markReversed(a, reason);
        audit.log("ALLOCATION_REVERSE", "PaymentAllocation", a.getId(),
                a.getPayment().getReceiptNo() + " / " + a.getInvoice().getInvoiceNumber() + " " + a.getAmount().toPlainString() + " reason: " + reason.trim());
    }

    /** Reverses a whole receipt (e.g. bounced cheque) and its active allocations. Refunded receipts cannot be reversed. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void reversePayment(Long paymentId, String reason) {
        requireReason(reason);
        Payment p = payments.lockById(paymentId).orElseThrow(() -> new BusinessException("Receipt not found"));
        if (p.getStatus() != RecordStatus.ACTIVE) throw new BusinessException("Receipt is already reversed");
        if (refunds.sumByPayment(p.getId()).signum() > 0) throw new BusinessException("This receipt has refunds recorded and cannot be reversed");
        List<PaymentAllocation> active = new ArrayList<>(allocations.findByPaymentIdAndStatus(p.getId(), RecordStatus.ACTIVE));
        active.sort(Comparator.comparing(x -> x.getInvoice().getId()));
        for (PaymentAllocation a : active) {
            invoices.lockById(a.getInvoice().getId());
            markReversed(a, "Receipt " + p.getReceiptNo() + " reversed: " + reason.trim());
        }
        p.setStatus(RecordStatus.REVERSED);
        p.setReversedAt(Instant.now());
        p.setReversedBy(SecurityUtil.username());
        p.setReverseReason(reason.trim());
        audit.log("RECEIPT_REVERSE", "Payment", p.getId(), p.getReceiptNo() + " " + p.getAmount().toPlainString() + " reason: " + reason.trim());
    }

    private void markReversed(PaymentAllocation a, String reason) {
        a.setStatus(RecordStatus.REVERSED);
        a.setReversedAt(Instant.now());
        a.setReversedBy(SecurityUtil.username());
        a.setReverseReason(reason.trim());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new BusinessException("A reason is required");
    }
}
