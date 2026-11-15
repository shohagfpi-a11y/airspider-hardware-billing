package com.hardwarebilling.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Customer account statement without double counting.
 *
 * Account balance (positive = customer owes us) = sum(invoices issued) - sum(receipts) + sum(refunds),
 * with voids and receipt reversals posted as opposite entries. Allocations ("receipt applied to invoice")
 * are shown as information only: they move money between documents, they never change the balance.
 * Identity: closing balance = outstanding receivables - available credit.
 */
@Service
@Transactional(readOnly = true)
public class StatementService {

    public record Line(LocalDate date, int order, long seq, String type, String ref, String description,
                       BigDecimal debit, BigDecimal credit, BigDecimal applied, BigDecimal balance) {}

    public record Statement(LocalDate from, LocalDate to, BigDecimal opening, List<Line> lines, BigDecimal closing) {}

    private final JdbcClient jdbc;
    private final CompanyService company;

    public StatementService(JdbcClient jdbc, CompanyService company) {
        this.jdbc = jdbc;
        this.company = company;
    }

    public Statement statement(Long customerId, LocalDate from, LocalDate to) {
        ZoneId zone = ZoneId.of(company.get().getTimezone());
        List<Line> events = new ArrayList<>();

        jdbc.sql("select id, invoice_number, doc_status, invoice_date, grand_total, voided_at from invoice where customer_id = ? and doc_status in ('ISSUED','VOIDED')")
                .param(customerId).query((rs, n) -> {
                    long id = rs.getLong("id");
                    String no = rs.getString("invoice_number");
                    BigDecimal total = rs.getBigDecimal("grand_total");
                    events.add(new Line(rs.getDate("invoice_date").toLocalDate(), 1, id, "Invoice", no, "Invoice issued", total, null, null, null));
                    OffsetDateTime v = rs.getObject("voided_at", OffsetDateTime.class);
                    if ("VOIDED".equals(rs.getString("doc_status")) && v != null) {
                        events.add(new Line(v.atZoneSameInstant(zone).toLocalDate(), 2, id, "Void", no, "Invoice voided", null, total, null, null));
                    }
                    return null;
                }).list();

        jdbc.sql("select id, receipt_no, payment_date, amount, method, reference, status, reversed_at from payment where customer_id = ?")
                .param(customerId).query((rs, n) -> {
                    long id = rs.getLong("id");
                    String no = rs.getString("receipt_no");
                    BigDecimal amount = rs.getBigDecimal("amount");
                    String ref = rs.getString("reference");
                    events.add(new Line(rs.getDate("payment_date").toLocalDate(), 3, id, "Receipt", no,
                            "Payment received (" + rs.getString("method").toLowerCase().replace('_', ' ') + (ref != null ? ", ref " + ref : "") + ")",
                            null, amount, null, null));
                    OffsetDateTime r = rs.getObject("reversed_at", OffsetDateTime.class);
                    if ("REVERSED".equals(rs.getString("status")) && r != null) {
                        events.add(new Line(r.atZoneSameInstant(zone).toLocalDate(), 4, id, "Reversal", no, "Receipt reversed", amount, null, null, null));
                    }
                    return null;
                }).list();

        jdbc.sql("""
                select a.id, p.receipt_no, i.invoice_number, a.amount, a.allocation_date, a.status
                from payment_allocation a join payment p on p.id = a.payment_id join invoice i on i.id = a.invoice_id
                where p.customer_id = ?
                """).param(customerId).query((rs, n) -> {
                    boolean reversed = "REVERSED".equals(rs.getString("status"));
                    events.add(new Line(rs.getDate("allocation_date").toLocalDate(), 5, rs.getLong("id"), "Applied",
                            rs.getString("receipt_no"), "Applied to invoice " + rs.getString("invoice_number") + (reversed ? " (later reversed)" : ""),
                            null, null, rs.getBigDecimal("amount"), null));
                    return null;
                }).list();

        jdbc.sql("select r.id, p.receipt_no, r.refund_date, r.amount from refund r join payment p on p.id = r.payment_id where r.customer_id = ?")
                .param(customerId).query((rs, n) -> {
                    events.add(new Line(rs.getDate("refund_date").toLocalDate(), 6, rs.getLong("id"), "Refund", rs.getString("receipt_no"),
                            "Credit refunded to customer", rs.getBigDecimal("amount"), null, null, null));
                    return null;
                }).list();

        events.sort(Comparator.comparing(Line::date).thenComparingInt(Line::order).thenComparingLong(Line::seq));

        int scale = Money.scaleFor(company.get().getCurrency());
        BigDecimal running = BigDecimal.ZERO.setScale(scale);
        BigDecimal opening = running;
        List<Line> out = new ArrayList<>();
        for (Line e : events) {
            if (e.debit() != null) running = running.add(e.debit());
            if (e.credit() != null) running = running.subtract(e.credit());
            if (from != null && e.date().isBefore(from)) { opening = running; continue; }
            if (to != null && e.date().isAfter(to)) continue;
            out.add(new Line(e.date(), e.order(), e.seq(), e.type(), e.ref(), e.description(), e.debit(), e.credit(), e.applied(), running));
        }
        BigDecimal closing = out.isEmpty() ? opening : out.get(out.size() - 1).balance();
        return new Statement(from, to, opening, out, closing);
    }
}
