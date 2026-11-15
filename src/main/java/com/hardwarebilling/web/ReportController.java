package com.hardwarebilling.web;

import com.hardwarebilling.config.SecurityUtil;
import com.hardwarebilling.domain.Enums.DocStatus;
import com.hardwarebilling.domain.Enums.PaymentStatus;
import com.hardwarebilling.service.*;
import com.hardwarebilling.service.ReportService.InvoiceFilter;
import com.hardwarebilling.service.ReportService.InvoiceRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tabular reports: one data builder feeds the screen table, the CSV export and the A4 print/PDF document. */
@Controller
@RequestMapping("/reports")
public class ReportController {

    public record Cell(String text, String href) {
        static Cell of(String t) { return new Cell(t, null); }
        static Cell link(String t, String h) { return new Cell(t, h); }
    }

    /** One headline figure shown above the table (label, value). */
    public record Stat(String label, String value) {}

    public record Table(String key, String title, String note, List<String> headers, List<Integer> numeric,
                        List<List<Cell>> rows, List<String> totals, List<Stat> summary) {}

    private final ReportService reports;
    private final CustomerService customers;
    private final CompanyService company;
    private final FileStorageService files;
    private final PdfService pdf;
    private final FmtBean fmt;

    public ReportController(ReportService reports, CustomerService customers, CompanyService company, FileStorageService files,
                            PdfService pdf, FmtBean fmt) {
        this.reports = reports;
        this.customers = customers;
        this.company = company;
        this.files = files;
        this.pdf = pdf;
        this.fmt = fmt;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("nav", "reports");
        return "reports";
    }

    @GetMapping("/{key}")
    public String show(@PathVariable String key, Filters f, Model model, HttpServletRequest req) {
        model.addAttribute("t", build(key, f));
        model.addAttribute("f", f);
        model.addAttribute("qs", req.getQueryString() == null ? "" : "?" + req.getQueryString());
        model.addAttribute("customers", customers.active());
        model.addAttribute("statuses", DocStatus.values());
        model.addAttribute("payStatuses", PaymentStatus.values());
        model.addAttribute("nav", "reports");
        return "report";
    }

    @GetMapping("/{key}.csv")
    public ResponseEntity<byte[]> csv(@PathVariable String key, Filters f) {
        Table t = build(key, f);
        List<List<String>> rows = new ArrayList<>();
        for (var r : t.rows()) rows.add(r.stream().map(Cell::text).toList());
        if (t.totals() != null) rows.add(t.totals());
        return ResponseEntity.ok().contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + key + ".csv\"")
                .body(Csv.write(t.headers(), rows));
    }

    /** A4 print view (same document as the PDF). */
    @GetMapping("/{key}/print")
    public ResponseEntity<String> print(@PathVariable String key, Filters f, HttpServletRequest req) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(pdf.html("report-document", docModel(key, f, req), true));
    }

    @GetMapping("/{key}.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String key, Filters f, HttpServletRequest req) {
        byte[] bytes = pdf.pdf("report-document", docModel(key, f, req));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + key + "-report.pdf\"").body(bytes);
    }

    private Map<String, Object> docModel(String key, Filters f, HttpServletRequest req) {
        Table t = build(key, f);
        var co = company.get();
        Map<String, Object> m = new HashMap<>();
        m.put("t", t);
        m.put("co", Snapshots.CompanySnap.of(co));
        m.put("logo", files.dataUri(co.getLogoFile()));
        m.put("filters", describe(key, f));
        m.put("generated", ZonedDateTime.now(ZoneId.of(co.getTimezone())).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        m.put("user", SecurityUtil.username());
        m.put("currency", co.getCurrency());
        m.put("landscape", t.headers().size() > 8);
        String qs = req.getQueryString() == null ? "" : req.getQueryString().replaceAll("&?autoprint=1", "");
        m.put("qs", qs.isEmpty() ? "" : "?" + qs);
        // single-line page footer for the CSS margin box (no quotes / line breaks)
        m.put("footer", Fmt.cssString(co.getDisplayName() + "  |  " + t.title()));
        return m;
    }

    private List<String> describe(String key, Filters f) {
        List<String> out = new ArrayList<>();
        if (key.equals("invoices") || key.equals("receipts")) {
            String basis = key.equals("invoices") ? "Invoice date" : "Payment date";
            if (f.getFrom() != null || f.getTo() != null) {
                out.add(basis + ": " + (f.getFrom() == null ? "beginning" : fmt.date(f.getFrom())) + " to " + (f.getTo() == null ? "today" : fmt.date(f.getTo())));
            } else {
                out.add(basis + ": all dates");
            }
        } else {
            out.add("Position as of " + fmt.date(company.today()));
        }
        if (!key.equals("credits")) {
            out.add("Customer: " + (f.getCustomerId() == null ? "All customers" : customers.get(f.getCustomerId()).getName()));
        }
        if (key.equals("invoices")) {
            out.add("Status: " + (f.getStatus() == null ? "Any" : cap(f.getStatus().name())));
            if (f.getPaymentStatus() != null) out.add("Payment: " + cap(f.getPaymentStatus().name()));
            if (f.isOverdue()) out.add("Overdue only");
            if (f.getQ() != null && !f.getQ().isBlank()) out.add("Search: \"" + f.getQ().trim() + "\"");
        }
        out.add("Amounts in " + fmt.currency());
        return out;
    }

    private static String cap(String enumName) {
        String s = enumName.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Filter parameters (bound from the query string). */
    public static class Filters {
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate from;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate to;
        private Long customerId;
        private DocStatus status;
        private PaymentStatus paymentStatus;
        private boolean overdue;
        private String q;
        public LocalDate getFrom() { return from; }
        public void setFrom(LocalDate v) { from = v; }
        public LocalDate getTo() { return to; }
        public void setTo(LocalDate v) { to = v; }
        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long v) { customerId = v; }
        public DocStatus getStatus() { return status; }
        public void setStatus(DocStatus v) { status = v; }
        public PaymentStatus getPaymentStatus() { return paymentStatus; }
        public void setPaymentStatus(PaymentStatus v) { paymentStatus = v; }
        public boolean isOverdue() { return overdue; }
        public void setOverdue(boolean v) { overdue = v; }
        public String getQ() { return q; }
        public void setQ(String v) { q = v; }
    }

    private Table build(String key, Filters f) {
        return switch (key) {
            case "invoices" -> invoices(f);
            case "outstanding" -> outstanding(f);
            case "receipts" -> receipts(f);
            case "credits" -> credits();
            default -> throw new BusinessException("Unknown report");
        };
    }

    private String status(InvoiceRow r) {
        String s = cap(r.status().name());
        if (r.status() == DocStatus.ISSUED) s += " / " + r.paymentStatus().name().replace('_', ' ').toLowerCase() + (r.overdue() ? " / OVERDUE" : "");
        return s;
    }

    private Table invoices(Filters f) {
        var rows = reports.invoices(new InvoiceFilter(f.getFrom(), f.getTo(), f.getCustomerId(), f.getStatus(), f.getPaymentStatus(), f.isOverdue(), f.getQ()), 5000);
        List<List<Cell>> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO, paid = BigDecimal.ZERO, bal = BigDecimal.ZERO;
        int issued = 0;
        for (var r : rows) {
            String no = r.number() == null ? "Draft #" + r.id() : r.number();
            out.add(List.of(Cell.link(no, "/invoices/" + r.id()), Cell.of(fmt.date(r.date())), Cell.of(fmt.date(r.due())), Cell.of(r.customer()),
                    Cell.of(status(r)), Cell.of(fmt.money(r.total())), Cell.of(fmt.money(r.paid())), Cell.of(fmt.money(r.balance()))));
            if (r.status() != DocStatus.VOIDED) { total = total.add(r.total()); paid = paid.add(r.paid()); bal = bal.add(r.balance()); }
            if (r.status() == DocStatus.ISSUED) issued++;
        }
        return new Table("invoices", "Invoice list", "Date basis: invoice date. Totals exclude voided invoices.",
                List.of("Invoice", "Invoice date", "Due date", "Customer", "Status", "Total", "Paid", "Balance"), List.of(5, 6, 7), out,
                List.of("Total (non-voided)", "", "", "", "", fmt.money(total), fmt.money(paid), fmt.money(bal)),
                List.of(new Stat("Invoices", String.valueOf(rows.size())), new Stat("Issued", String.valueOf(issued)),
                        new Stat("Invoiced value", fmt.money(total)), new Stat("Balance due", fmt.money(bal))));
    }

    private Table outstanding(Filters f) {
        var rows = reports.outstanding(f.getCustomerId());
        List<List<Cell>> out = new ArrayList<>();
        BigDecimal bal = BigDecimal.ZERO, overdue = BigDecimal.ZERO;
        int overdueCount = 0;
        for (var r : rows) {
            out.add(List.of(Cell.link(r.number(), "/invoices/" + r.id()), Cell.of(fmt.date(r.date())), Cell.of(fmt.date(r.due())), Cell.of(r.customer()),
                    Cell.of(r.overdue() ? r.daysOverdue() + " days overdue" : "Not yet due"), Cell.of(fmt.money(r.total())),
                    Cell.of(fmt.money(r.paid())), Cell.of(fmt.money(r.balance()))));
            bal = bal.add(r.balance());
            if (r.overdue()) { overdue = overdue.add(r.balance()); overdueCount++; }
        }
        return new Table("outstanding", "Outstanding invoices", "Issued invoices with an unpaid balance as of today. Advance credit is not netted here (see Customer advances & credit).",
                List.of("Invoice", "Invoice date", "Due date", "Customer", "Due status", "Total", "Paid", "Balance"), List.of(5, 6, 7), out,
                List.of("Total outstanding", "", "", "", "", "", "", fmt.money(bal)),
                List.of(new Stat("Open invoices", String.valueOf(rows.size())), new Stat("Total outstanding", fmt.money(bal)),
                        new Stat("Overdue invoices", String.valueOf(overdueCount)), new Stat("Overdue amount", fmt.money(overdue))));
    }

    private Table receipts(Filters f) {
        var rows = reports.receipts(f.getFrom(), f.getTo(), f.getCustomerId(), 5000);
        List<List<Cell>> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO, applied = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        int active = 0;
        for (var r : rows) {
            out.add(List.of(Cell.link(r.receiptNo(), "/payments/" + r.id()), Cell.of(fmt.date(r.date())), Cell.of(r.customer()), Cell.of(cap(r.method())),
                    Cell.of(r.reference() == null ? "" : r.reference()), Cell.of(cap(r.status())), Cell.of(fmt.money(r.amount())),
                    Cell.of(fmt.money(r.allocated())), Cell.of(fmt.money(r.refunded())), Cell.of(fmt.money(r.available()))));
            if ("ACTIVE".equals(r.status())) { total = total.add(r.amount()); applied = applied.add(r.allocated()); credit = credit.add(r.available()); active++; }
        }
        return new Table("receipts", "Payment receipts", "Date basis: payment date. Money actually received; applying an advance is an allocation, not a new receipt. Reversed receipts are excluded from totals.",
                List.of("Receipt", "Date", "Customer", "Method", "Reference", "Status", "Amount", "Applied", "Refunded", "Credit left"),
                List.of(6, 7, 8, 9), out, List.of("Total received (active)", "", "", "", "", "", fmt.money(total), fmt.money(applied), "", fmt.money(credit)),
                List.of(new Stat("Receipts", String.valueOf(active)), new Stat("Total received", fmt.money(total)),
                        new Stat("Applied to invoices", fmt.money(applied)), new Stat("Credit left", fmt.money(credit))));
    }

    private Table credits() {
        var rows = reports.credits().stream().filter(c -> c.outstanding().signum() != 0 || c.credit().signum() != 0).toList();
        List<List<Cell>> out = new ArrayList<>();
        BigDecimal o = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (var r : rows) {
            out.add(List.of(Cell.link(r.code(), "/customers/" + r.customerId()), Cell.of(r.customer()), Cell.of(fmt.money(r.outstanding())),
                    Cell.of(fmt.money(r.credit())), Cell.of(fmt.money(r.outstanding().subtract(r.credit())))));
            o = o.add(r.outstanding());
            c = c.add(r.credit());
        }
        return new Table("credits", "Customer advances & credit", "Receivables (what customers owe) and available credit (advance money held) are shown separately. Net = receivables - credit.",
                List.of("Code", "Customer", "Outstanding receivable", "Available credit", "Net position"), List.of(2, 3, 4), out,
                List.of("Total", "", fmt.money(o), fmt.money(c), fmt.money(o.subtract(c))),
                List.of(new Stat("Customers", String.valueOf(rows.size())), new Stat("Receivables", fmt.money(o)),
                        new Stat("Credit held", fmt.money(c)), new Stat("Net position", fmt.money(o.subtract(c)))));
    }
}
