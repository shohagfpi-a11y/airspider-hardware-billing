package com.hardwarebilling.service;

import com.hardwarebilling.domain.Enums.DocStatus;
import com.hardwarebilling.domain.Enums.PaymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Read-only queries for lists, reports and the dashboard. All amounts come from the same tables the documents use. */
@Service
@Transactional(readOnly = true)
public class ReportService {

    public record InvoiceRow(Long id, String number, DocStatus status, Long customerId, String customer,
                             LocalDate date, LocalDate due, BigDecimal total, BigDecimal paid,
                             BigDecimal balance, PaymentStatus paymentStatus, boolean overdue, long daysOverdue) {}

    public record ReceiptRow(Long id, String receiptNo, LocalDate date, Long customerId, String customer, String method,
                             String reference, BigDecimal amount, BigDecimal allocated, BigDecimal refunded,
                             BigDecimal available, String status) {}

    public record CreditRow(Long customerId, String code, String customer, BigDecimal outstanding, BigDecimal credit) {}

    public record Dashboard(LocalDate from, LocalDate to, BigDecimal invoicedValue, long invoicedCount,
                            BigDecimal received, long receivedCount, BigDecimal outstanding,
                            BigDecimal overdueAmount, long overdueCount, BigDecimal availableCredit,
                            List<InvoiceRow> recentInvoices, List<ReceiptRow> recentReceipts) {}

    public record InvoiceFilter(LocalDate from, LocalDate to, Long customerId, DocStatus status,
                                PaymentStatus paymentStatus, boolean overdueOnly, String q) {}

    private final JdbcClient jdbc;
    private final CompanyService company;

    public ReportService(JdbcClient jdbc, CompanyService company) {
        this.jdbc = jdbc;
        this.company = company;
    }

    private static final String INVOICE_SQL = """
        select i.id, i.invoice_number, i.doc_status, c.id as customer_id, c.name as customer, i.invoice_date, i.due_date,
               i.grand_total, coalesce(a.paid, 0) as paid
        from invoice i
        join customer c on c.id = i.customer_id
        left join (select invoice_id, sum(amount) as paid from payment_allocation where status = 'ACTIVE' group by invoice_id) a
               on a.invoice_id = i.id
        """;

    public List<InvoiceRow> invoices(InvoiceFilter f, int limit) {
        LocalDate today = company.today();
        StringBuilder sql = new StringBuilder(INVOICE_SQL).append(" where 1=1");
        List<Object> params = new ArrayList<>();
        if (f.from() != null) { sql.append(" and i.invoice_date >= ?"); params.add(Date.valueOf(f.from())); }
        if (f.to() != null) { sql.append(" and i.invoice_date <= ?"); params.add(Date.valueOf(f.to())); }
        if (f.customerId() != null) { sql.append(" and i.customer_id = ?"); params.add(f.customerId()); }
        if (f.status() != null) { sql.append(" and i.doc_status = ?"); params.add(f.status().name()); }
        if (f.q() != null && !f.q().isBlank()) {
            sql.append(" and (lower(coalesce(i.invoice_number,'')) like ? or lower(c.name) like ? or lower(coalesce(i.lpo_no,'')) like ?)");
            String like = "%" + f.q().trim().toLowerCase().replace("%", "").replace("_", "") + "%";
            params.add(like); params.add(like); params.add(like);
        }
        sql.append(" order by i.invoice_date desc, i.id desc");
        boolean javaFilters = f.paymentStatus() != null || f.overdueOnly();
        if (!javaFilters) sql.append(" limit ").append(Math.max(1, limit));
        var spec = jdbc.sql(sql.toString());
        for (Object p : params) spec = spec.param(p);
        List<InvoiceRow> rows = spec.query((rs, n) -> row(rs, today)).list();
        return rows.stream()
                .filter(r -> f.paymentStatus() == null || (r.status() == DocStatus.ISSUED && r.paymentStatus() == f.paymentStatus()))
                .filter(r -> !f.overdueOnly() || r.overdue())
                .limit(limit)
                .toList();
    }

    private InvoiceRow row(java.sql.ResultSet rs, LocalDate today) throws java.sql.SQLException {
        DocStatus st = DocStatus.valueOf(rs.getString("doc_status"));
        BigDecimal total = rs.getBigDecimal("grand_total");
        BigDecimal paid = rs.getBigDecimal("paid");
        LocalDate due = rs.getDate("due_date").toLocalDate();
        BigDecimal balance = st == DocStatus.ISSUED ? total.subtract(paid) : BigDecimal.ZERO;
        PaymentStatus ps = st != DocStatus.ISSUED || paid.signum() <= 0 ? PaymentStatus.UNPAID
                : (paid.compareTo(total) >= 0 ? PaymentStatus.PAID : PaymentStatus.PARTIALLY_PAID);
        boolean overdue = st == DocStatus.ISSUED && balance.signum() > 0 && due.isBefore(today);
        long days = overdue ? java.time.temporal.ChronoUnit.DAYS.between(due, today) : 0;
        return new InvoiceRow(rs.getLong("id"), rs.getString("invoice_number"), st, rs.getLong("customer_id"),
                rs.getString("customer"), rs.getDate("invoice_date").toLocalDate(), due, total, paid, balance, ps, overdue, days);
    }

    public record AllocRow(Long id, Long paymentId, String receiptNo, LocalDate date, BigDecimal amount, String status, String reason) {}

    public List<AllocRow> allocationsForInvoice(Long invoiceId) {
        return jdbc.sql("""
                select a.id, a.payment_id, p.receipt_no, a.allocation_date, a.amount, a.status, a.reverse_reason
                from payment_allocation a join payment p on p.id = a.payment_id
                where a.invoice_id = ? order by a.id
                """).param(invoiceId).query((rs, n) -> new AllocRow(rs.getLong("id"), rs.getLong("payment_id"), rs.getString("receipt_no"),
                rs.getDate("allocation_date").toLocalDate(), rs.getBigDecimal("amount"), rs.getString("status"), rs.getString("reverse_reason"))).list();
    }

    public List<AllocRow> allocationsForPayment(Long paymentId) {
        return jdbc.sql("""
                select a.id, a.invoice_id as payment_id, i.invoice_number as receipt_no, a.allocation_date, a.amount, a.status, a.reverse_reason
                from payment_allocation a join invoice i on i.id = a.invoice_id
                where a.payment_id = ? order by a.id
                """).param(paymentId).query((rs, n) -> new AllocRow(rs.getLong("id"), rs.getLong("payment_id"), rs.getString("receipt_no"),
                rs.getDate("allocation_date").toLocalDate(), rs.getBigDecimal("amount"), rs.getString("status"), rs.getString("reverse_reason"))).list();
    }

    public List<InvoiceRow> outstanding(Long customerId) {
        return invoices(new InvoiceFilter(null, null, customerId, DocStatus.ISSUED, null, false, null), 5000).stream()
                .filter(r -> r.balance().signum() > 0)
                .sorted(java.util.Comparator.comparing(InvoiceRow::due).thenComparing(InvoiceRow::id))
                .toList();
    }

    private static final String RECEIPT_SQL = """
        select p.id, p.receipt_no, p.payment_date, c.id as customer_id, c.name as customer, p.method, p.reference,
               p.amount, p.status,
               coalesce((select sum(a.amount) from payment_allocation a where a.payment_id = p.id and a.status = 'ACTIVE'), 0) as allocated,
               coalesce((select sum(r.amount) from refund r where r.payment_id = p.id), 0) as refunded
        from payment p join customer c on c.id = p.customer_id
        """;

    public List<ReceiptRow> receipts(LocalDate from, LocalDate to, Long customerId, int limit) {
        StringBuilder sql = new StringBuilder(RECEIPT_SQL).append(" where 1=1");
        List<Object> params = new ArrayList<>();
        if (from != null) { sql.append(" and p.payment_date >= ?"); params.add(Date.valueOf(from)); }
        if (to != null) { sql.append(" and p.payment_date <= ?"); params.add(Date.valueOf(to)); }
        if (customerId != null) { sql.append(" and p.customer_id = ?"); params.add(customerId); }
        sql.append(" order by p.payment_date desc, p.id desc limit ").append(limit);
        var spec = jdbc.sql(sql.toString());
        for (Object p : params) spec = spec.param(p);
        return spec.query((rs, n) -> {
            boolean active = "ACTIVE".equals(rs.getString("status"));
            BigDecimal amount = rs.getBigDecimal("amount");
            BigDecimal allocated = rs.getBigDecimal("allocated");
            BigDecimal refunded = rs.getBigDecimal("refunded");
            return new ReceiptRow(rs.getLong("id"), rs.getString("receipt_no"), rs.getDate("payment_date").toLocalDate(),
                    rs.getLong("customer_id"), rs.getString("customer"), rs.getString("method"), rs.getString("reference"),
                    amount, allocated, refunded, active ? amount.subtract(allocated).subtract(refunded) : BigDecimal.ZERO,
                    rs.getString("status"));
        }).list();
    }

    public List<ReceiptRow> receiptsWithCredit(Long customerId) {
        return receipts(null, null, customerId, 2000).stream()
                .filter(r -> "ACTIVE".equals(r.status()) && r.available().signum() > 0).toList();
    }

    /** Per customer: receivables (what they owe) and available advance credit (what we hold), kept separate. */
    public List<CreditRow> credits() {
        return credits(null);
    }

    private List<CreditRow> credits(Long customerId) {
        String where = customerId == null ? "" : " where c.id = " + customerId;   // numeric id, never user text
        return jdbc.sql(CREDIT_SQL.replace("/*WHERE*/", where)).query((rs, n) -> new CreditRow(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"), rs.getBigDecimal("outstanding"), rs.getBigDecimal("credit"))).list();
    }

    private static final String CREDIT_SQL = """
            select c.id, c.code, c.name,
              coalesce((select sum(i.grand_total) from invoice i where i.customer_id = c.id and i.doc_status = 'ISSUED'), 0)
              - coalesce((select sum(a.amount) from payment_allocation a join invoice i on i.id = a.invoice_id
                          where i.customer_id = c.id and i.doc_status = 'ISSUED' and a.status = 'ACTIVE'), 0) as outstanding,
              coalesce((select sum(p.amount) from payment p where p.customer_id = c.id and p.status = 'ACTIVE'), 0)
              - coalesce((select sum(a.amount) from payment_allocation a join payment p on p.id = a.payment_id
                          where p.customer_id = c.id and p.status = 'ACTIVE' and a.status = 'ACTIVE'), 0)
              - coalesce((select sum(r.amount) from refund r join payment p on p.id = r.payment_id
                          where p.customer_id = c.id and p.status = 'ACTIVE'), 0) as credit
            from customer c /*WHERE*/ order by c.name
            """;

    public CreditRow customerPosition(Long customerId) {
        return credits(customerId).stream().findFirst()
                .orElse(new CreditRow(customerId, "", "", BigDecimal.ZERO, BigDecimal.ZERO));
    }

    public Dashboard dashboard(LocalDate from, LocalDate to) {
        BigDecimal invoiced = jdbc.sql("select coalesce(sum(grand_total),0) from invoice where doc_status = 'ISSUED' and invoice_date between ? and ?")
                .param(Date.valueOf(from)).param(Date.valueOf(to)).query(BigDecimal.class).single();
        long invoicedCount = jdbc.sql("select count(*) from invoice where doc_status = 'ISSUED' and invoice_date between ? and ?")
                .param(Date.valueOf(from)).param(Date.valueOf(to)).query(Long.class).single();
        BigDecimal received = jdbc.sql("select coalesce(sum(amount),0) from payment where status = 'ACTIVE' and payment_date between ? and ?")
                .param(Date.valueOf(from)).param(Date.valueOf(to)).query(BigDecimal.class).single();
        long receivedCount = jdbc.sql("select count(*) from payment where status = 'ACTIVE' and payment_date between ? and ?")
                .param(Date.valueOf(from)).param(Date.valueOf(to)).query(Long.class).single();
        List<InvoiceRow> open = outstanding(null);
        BigDecimal outstanding = open.stream().map(InvoiceRow::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<InvoiceRow> overdue = open.stream().filter(InvoiceRow::overdue).toList();
        BigDecimal overdueAmt = overdue.stream().map(InvoiceRow::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = credits().stream().map(CreditRow::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Dashboard(from, to, invoiced, invoicedCount, received, receivedCount, outstanding, overdueAmt,
                overdue.size(), credit,
                invoices(new InvoiceFilter(null, null, null, null, null, false, null), 8),
                receipts(null, null, null, 8));
    }
}
