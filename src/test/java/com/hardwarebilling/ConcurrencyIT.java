package com.hardwarebilling;

import com.hardwarebilling.domain.*;
import com.hardwarebilling.service.BusinessException;
import com.hardwarebilling.service.ItemService.ItemForm;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyIT extends AbstractIT {

    private <T> List<Future<T>> runParallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                asAdmin();                       // each worker thread needs its own security context
                try {
                    start.await();
                    return task.call();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        for (Future<T> f : futures) {
            try { f.get(60, TimeUnit.SECONDS); } catch (ExecutionException ignored) { /* inspected by the caller */ }
        }
        pool.shutdown();
        return futures;
    }

    @Test
    void concurrentAllocationsCannotOverspendOneReceipt() throws Exception {
        Customer c = newCustomer();
        Payment p = payments.createReceipt(receipt(c, "100"));
        // 8 invoices of 100 each; 8 threads all try to spend the same 100 of credit on a different invoice
        List<Invoice> invs = new ArrayList<>();
        for (int i = 0; i < 8; i++) invs.add(issued(c, "100"));
        AtomicInteger idx = new AtomicInteger();
        var futures = runParallel(8, () -> payments.allocate(p.getId(), invs.get(idx.getAndIncrement()).getId(), bd("100"), null));

        long ok = futures.stream().filter(f -> { try { f.get(); return true; } catch (Exception e) { return false; } }).count();
        long rejected = futures.stream().filter(f -> { try { f.get(); return false; } catch (Exception e) { return e.getCause() instanceof BusinessException; } }).count();
        assertThat(ok).isEqualTo(1);
        assertThat(rejected).isEqualTo(7);
        assertThat(payments.availableCredit(payments.get(p.getId()))).isEqualByComparingTo("0");
        BigDecimal allocated = reports.receipts(null, null, c.getId(), 5).get(0).allocated();
        assertThat(allocated).isEqualByComparingTo("100");
    }

    @Test
    void concurrentAllocationsCannotOverpayOneInvoice() throws Exception {
        Customer c = newCustomer();
        Invoice inv = issued(c, "100");
        List<Payment> pays = new ArrayList<>();
        for (int i = 0; i < 6; i++) pays.add(payments.createReceipt(receipt(c, "100")));
        AtomicInteger idx = new AtomicInteger();
        runParallel(6, () -> payments.allocate(pays.get(idx.getAndIncrement()).getId(), inv.getId(), bd("100"), null));
        assertThat(invoices.allocated(inv.getId())).isEqualByComparingTo("100");   // never more than the invoice total
    }

    @Test
    void concurrentRefundAndAllocationCannotSpendTheSameMoney() throws Exception {
        Customer c = newCustomer();
        Invoice inv = issued(c, "100");
        Payment p = payments.createReceipt(receipt(c, "100"));
        AtomicInteger n = new AtomicInteger();
        runParallel(4, () -> n.getAndIncrement() % 2 == 0
                ? payments.allocate(p.getId(), inv.getId(), bd("100"), null)
                : payments.refund(p.getId(), bd("100"), null, Enums.PaymentMethod.CASH, null, null));
        BigDecimal allocated = invoices.allocated(inv.getId());
        BigDecimal credit = payments.availableCredit(payments.get(p.getId()));
        BigDecimal refunded = reports.receipts(null, null, c.getId(), 5).get(0).refunded();
        assertThat(credit.signum()).isGreaterThanOrEqualTo(0);                       // never negative
        assertThat(allocated.add(refunded)).isLessThanOrEqualTo(bd("100"));         // money spent at most once
    }

    @Test
    void invoiceNumbersAreUniqueAndGapFreeUnderConcurrency() throws Exception {
        Customer c = newCustomer();
        List<Long> draftIds = new ArrayList<>();
        for (int i = 0; i < 12; i++) draftIds.add(draft(c, line("n", "1", "5", "0")).getId());
        AtomicInteger idx = new AtomicInteger();
        var futures = runParallel(12, () -> invoices.issue(draftIds.get(idx.getAndIncrement())).getInvoiceNumber());
        List<String> numbers = new ArrayList<>();
        for (var f : futures) numbers.add(f.get());
        assertThat(Set.copyOf(numbers)).hasSize(12);
        List<Long> nums = numbers.stream().map(s -> Long.parseLong(s.replaceAll("\\D", ""))).sorted().toList();
        assertThat(nums.get(11) - nums.get(0)).isEqualTo(11);   // consecutive, no gaps
    }

    @Test
    void itemCodesAreUniqueUnderConcurrentCreation() throws Exception {
        var futures = runParallel(15, () -> {
            ItemForm f = new ItemForm();
            f.setName("Concurrent item");
            return items.create(f).getCode();
        });
        List<String> codes = new ArrayList<>();
        for (var f : futures) codes.add(f.get());
        assertThat(codes.stream().collect(Collectors.toSet())).hasSize(15);
        assertThat(codes).allMatch(code -> code.matches("P\\d{5}"));
    }

    @Test
    void duplicateReceiptSubmissionsInParallelCreateOneReceipt() throws Exception {
        Customer c = newCustomer();
        var req = receipt(c, "55");
        runParallel(6, () -> {
            try {
                return payments.createReceipt(req).getId();
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                return payments.findByKey(req.idempotencyKey()).orElseThrow().getId();
            }
        });
        assertThat(reports.receipts(null, null, c.getId(), 10)).hasSize(1);
    }
}
