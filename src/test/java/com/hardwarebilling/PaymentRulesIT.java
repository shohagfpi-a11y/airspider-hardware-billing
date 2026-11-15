package com.hardwarebilling;

import com.hardwarebilling.domain.*;
import com.hardwarebilling.domain.Enums.DocStatus;
import com.hardwarebilling.domain.Enums.PaymentMethod;
import com.hardwarebilling.domain.Enums.PaymentStatus;
import com.hardwarebilling.service.BusinessException;
import com.hardwarebilling.service.InvoiceService;
import com.hardwarebilling.service.PaymentService.ReceiptRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRulesIT extends AbstractIT {

    private BigDecimal balance(Invoice i) {
        return InvoiceService.balance(invoices.get(i.getId()), invoices.allocated(i.getId()));
    }

    @Test
    void acceptanceScenario_advance300_invoice1000_total_receipts_1050() {
        Customer c = newCustomer();
        // 1. customer pays AED 300 in advance
        Payment advance = payments.createReceipt(receipt(c, "300"));
        assertThat(payments.availableCredit(advance)).isEqualByComparingTo("300");
        // 2. invoice of AED 1,000 is issued
        Invoice inv = issued(c, "1000");
        assertThat(balance(inv)).isEqualByComparingTo("1000");
        // 3. apply the advance: balance 700. This is an allocation, not a new receipt
        payments.allocate(advance.getId(), inv.getId(), bd("300"), null);
        assertThat(balance(inv)).isEqualByComparingTo("700");
        assertThat(payments.availableCredit(payments.get(advance.getId()))).isEqualByComparingTo("0");
        // 4. receive and allocate 500: balance 200
        payments.createReceipt(receipt(c, "500", alloc(inv, "500")));
        assertThat(balance(inv)).isEqualByComparingTo("200");
        assertThat(InvoiceService.paymentStatus(invoices.get(inv.getId()), invoices.allocated(inv.getId()))).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        // 5. receive 250, allocate 200: paid, AED 50 remains as customer credit
        Payment last = payments.createReceipt(receipt(c, "250", alloc(inv, "200")));
        assertThat(balance(inv)).isEqualByComparingTo("0");
        assertThat(InvoiceService.paymentStatus(invoices.get(inv.getId()), invoices.allocated(inv.getId()))).isEqualTo(PaymentStatus.PAID);
        assertThat(payments.availableCredit(payments.get(last.getId()))).isEqualByComparingTo("50");

        // 6. actual receipts total 1,050: applying the advance did not add a receipt
        var receipts = reports.receipts(null, null, c.getId(), 100);
        assertThat(receipts).hasSize(3);
        assertThat(receipts.stream().map(ReportRow -> ReportRow.amount()).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1050");
        var pos = reports.customerPosition(c.getId());
        assertThat(pos.outstanding()).isEqualByComparingTo("0");
        assertThat(pos.credit()).isEqualByComparingTo("50");

        // statement: no double counting, closing balance = outstanding - credit = -50
        var st = statements.statement(c.getId(), null, null);
        assertThat(st.closing()).isEqualByComparingTo("-50");
        assertThat(pos.outstanding().subtract(pos.credit())).isEqualByComparingTo(st.closing());
        long appliedRows = st.lines().stream().filter(l -> l.type().equals("Applied")).count();
        assertThat(appliedRows).isEqualTo(3);   // information rows only
    }

    @Test
    void allocationCannotExceedInvoiceBalanceOrAvailableCredit() {
        Customer c = newCustomer();
        Invoice inv = issued(c, "100");
        Payment big = payments.createReceipt(receipt(c, "500"));
        assertThatThrownBy(() -> payments.allocate(big.getId(), inv.getId(), bd("100.01"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exceeds the invoice balance");
        Invoice inv2 = issued(c, "1000");
        assertThatThrownBy(() -> payments.allocate(big.getId(), inv2.getId(), bd("500.01"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exceeds the available credit");
        // receipt creation itself may not allocate more than it received
        assertThatThrownBy(() -> payments.createReceipt(receipt(c, "50", alloc(inv2, "60"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exceeds the amount received");
    }

    @Test
    void paymentCannotBeAllocatedToAnotherCustomersInvoice() {
        Customer a = newCustomer(), b = newCustomer();
        Invoice invB = issued(b, "100");
        Payment pa = payments.createReceipt(receipt(a, "100"));
        assertThatThrownBy(() -> payments.allocate(pa.getId(), invB.getId(), bd("100"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("another customer");
    }

    @Test
    void draftAndVoidedInvoicesCannotReceivePayments() {
        Customer c = newCustomer();
        Invoice d = draft(c, line("x", "1", "10", "0"));
        Payment p = payments.createReceipt(receipt(c, "10"));
        assertThatThrownBy(() -> payments.allocate(p.getId(), d.getId(), bd("10"), null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void overpaymentIsRetainedAsCustomerCredit() {
        Customer c = newCustomer();
        Invoice inv = issued(c, "80");
        Payment p = payments.createReceipt(receipt(c, "100", alloc(inv, "80")));
        assertThat(payments.availableCredit(payments.get(p.getId()))).isEqualByComparingTo("20");
        assertThat(reports.customerPosition(c.getId()).credit()).isEqualByComparingTo("20");
    }

    @Test
    void duplicateReceiptSubmissionReturnsSameReceipt() {
        Customer c = newCustomer();
        ReceiptRequest r = receipt(c, "75");
        Payment first = payments.createReceipt(r);
        Payment second = payments.createReceipt(r);   // same idempotency key (double click / refresh)
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(reports.receipts(null, null, c.getId(), 10)).hasSize(1);
    }

    @Test
    void amountsWithMoreDecimalsThanTheCurrencyAreRejected() {
        Customer c = newCustomer();
        assertThatThrownBy(() -> payments.createReceipt(receipt(c, "10.555"))).isInstanceOf(BusinessException.class);
    }

    @Test
    void reversalRestoresBalanceAndVoidRequiresReversalFirst() {
        Customer c = newCustomer();
        Invoice inv = issued(c, "200");
        Payment p = payments.createReceipt(receipt(c, "200", alloc(inv, "200")));
        assertThat(balance(inv)).isEqualByComparingTo("0");

        assertThatThrownBy(() -> invoices.voidInvoice(inv.getId(), "mistake")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Reverse the allocations first");

        Long allocId = reports.allocationsForInvoice(inv.getId()).get(0).id();
        assertThatThrownBy(() -> payments.reverseAllocation(allocId, " ")).isInstanceOf(BusinessException.class);   // reason required
        payments.reverseAllocation(allocId, "applied to wrong invoice");
        assertThat(balance(inv)).isEqualByComparingTo("200");
        assertThat(payments.availableCredit(payments.get(p.getId()))).isEqualByComparingTo("200");   // money is free again
        assertThatThrownBy(() -> payments.reverseAllocation(allocId, "again")).isInstanceOf(BusinessException.class);   // not twice

        invoices.voidInvoice(inv.getId(), "customer cancelled");
        assertThat(invoices.get(inv.getId()).getDocStatus()).isEqualTo(DocStatus.VOIDED);
        assertThat(balance(inv)).isEqualByComparingTo("0");
        // the audit trail keeps everything: reversed rows are never deleted
        assertThat(reports.allocationsForInvoice(inv.getId())).hasSize(1).allMatch(a -> a.status().equals("REVERSED"));
    }

    @Test
    void reversingAReceiptReversesItsAllocationsAndRemovesItsCredit() {
        Customer c = newCustomer();
        Invoice inv = issued(c, "100");
        Payment p = payments.createReceipt(receipt(c, "100", alloc(inv, "100")));
        payments.reversePayment(p.getId(), "cheque bounced");
        assertThat(payments.get(p.getId()).getStatus()).isEqualTo(Enums.RecordStatus.REVERSED);
        assertThat(balance(inv)).isEqualByComparingTo("100");
        assertThat(reports.customerPosition(c.getId()).credit()).isEqualByComparingTo("0");
        var st = statements.statement(c.getId(), null, null);
        assertThat(st.closing()).isEqualByComparingTo("100");   // owes the invoice again, receipt + reversal net to zero
    }

    @Test
    void refundIsSeparateFromReversalAndLimitedToAvailableCredit() {
        Customer c = newCustomer();
        Payment p = payments.createReceipt(receipt(c, "100"));
        assertThatThrownBy(() -> payments.refund(p.getId(), bd("100.01"), null, PaymentMethod.CASH, null, null)).isInstanceOf(BusinessException.class);
        payments.refund(p.getId(), bd("40"), null, PaymentMethod.BANK_TRANSFER, "REF1", "returned advance");
        assertThat(payments.availableCredit(payments.get(p.getId()))).isEqualByComparingTo("60");
        assertThatThrownBy(() -> payments.reversePayment(p.getId(), "x")).hasMessageContaining("refunds");
        assertThat(statements.statement(c.getId(), null, null).closing()).isEqualByComparingTo("-60");
    }

    @Test
    void staffCannotRefundReverseOrVoid() {
        Customer c = newCustomer();
        Invoice inv = issued(c, "100");
        Payment p = payments.createReceipt(receipt(c, "100", alloc(inv, "100")));
        as("STAFF");
        assertThatThrownBy(() -> payments.refund(p.getId(), bd("1"), null, PaymentMethod.CASH, null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> payments.reversePayment(p.getId(), "x")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> invoices.voidInvoice(inv.getId(), "x")).isInstanceOf(AccessDeniedException.class);
        // but routine work is allowed
        Payment more = payments.createReceipt(receipt(c, "10"));
        assertThat(more.getReceiptNo()).isNotBlank();
    }

    @Test
    void readOnlyUsersCannotWriteAnything() {
        Customer c = newCustomer();
        as("READ_ONLY");
        assertThatThrownBy(() -> payments.createReceipt(receipt(c, "10"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> draft(c, line("x", "1", "1", "0"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> items.create(new com.hardwarebilling.service.ItemService.ItemForm())).isInstanceOf(AccessDeniedException.class);
        assertThat(reports.receipts(null, null, c.getId(), 5)).isEmpty();   // reading is fine
    }

    @Test
    void outstandingReportAndOverdueAreDerived() {
        Customer c = newCustomer();
        var today = company.today();
        Invoice d = invoices.saveDraft(new com.hardwarebilling.service.InvoiceRequests.InvoiceRequest(null, null, c.getId(), today.minusDays(40), today.minusDays(10),
                null, null, null, null, null, null, List.of(line("old", "1", "300", "0"))));
        Invoice inv = invoices.issue(d.getId());
        var rows = reports.outstanding(c.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).overdue()).isTrue();
        assertThat(rows.get(0).daysOverdue()).isEqualTo(10);
        payments.createReceipt(receipt(c, "300", alloc(inv, "300")));
        assertThat(reports.outstanding(c.getId())).isEmpty();
    }
}
