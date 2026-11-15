package com.hardwarebilling.web;

import com.hardwarebilling.domain.Customer;
import com.hardwarebilling.service.*;
import com.hardwarebilling.service.CustomerService.CustomerForm;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/customers")
public class CustomerController {
    private final CustomerService customers;
    private final ReportService reports;
    private final DocumentService documents;
    private final PdfService pdf;
    private final StatementService statements;
    private final CompanyService company;

    public CustomerController(CustomerService customers, ReportService reports, DocumentService documents, PdfService pdf,
                              StatementService statements, CompanyService company) {
        this.customers = customers;
        this.reports = reports;
        this.documents = documents;
        this.pdf = pdf;
        this.statements = statements;
        this.company = company;
    }

    @GetMapping
    public String list(@RequestParam(required = false, defaultValue = "") String q, Model model) {
        model.addAttribute("customers", customers.search(q));
        model.addAttribute("positions", reports.credits().stream().collect(java.util.stream.Collectors.toMap(ReportService.CreditRow::customerId, c -> c)));
        model.addAttribute("q", q);
        model.addAttribute("nav", "customers");
        return "customers";
    }

    @GetMapping("/new")
    public String create(Model model) {
        return form(new CustomerForm(), null, model);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        Customer c = customers.get(id);
        CustomerForm f = new CustomerForm();
        f.setId(c.getId()); f.setName(c.getName()); f.setContactPerson(c.getContactPerson()); f.setMobile(c.getMobile());
        f.setPhone(c.getPhone()); f.setEmail(c.getEmail()); f.setBillingAddress(c.getBillingAddress());
        f.setTaxNumber(c.getTaxNumber()); f.setDefaultPaymentTermsDays(c.getDefaultPaymentTermsDays());
        f.setNotes(c.getNotes()); f.setActive(c.isActive());
        return form(f, c.getCode(), model);
    }

    private String form(CustomerForm f, String code, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("code", code);
        model.addAttribute("nav", "customers");
        return "customer-form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") CustomerForm f, BindingResult br, Model model, RedirectAttributes ra) {
        String code = f.getId() == null ? null : customers.get(f.getId()).getCode();
        if (br.hasErrors()) return form(f, code, model);
        try {
            Customer c = customers.save(f);
            ra.addFlashAttribute("success", "Customer " + c.getCode() + " saved");
            return "redirect:/customers/" + c.getId();
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return form(f, code, model);
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Customer c = customers.get(id);
        model.addAttribute("c", c);
        model.addAttribute("pos", reports.customerPosition(id));
        model.addAttribute("invoices", reports.invoices(new ReportService.InvoiceFilter(null, null, id, null, null, false, null), 200));
        model.addAttribute("receipts", reports.receipts(null, null, id, 200));
        model.addAttribute("applied", statements.statement(id, null, null).lines().stream().filter(l -> l.type().equals("Applied")).toList());
        model.addAttribute("nav", "customers");
        return "customer-detail";
    }

    // ---- statements: HTML print view, PDF, CSV (all from the same StatementService data)

    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate today = company.today();
        return new LocalDate[]{from == null ? today.minusMonths(3) : from, to == null ? today : to};
    }

    @GetMapping("/{id}/statement")
    public ResponseEntity<String> statementHtml(@PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = range(from, to);
        var doc = documents.statement(customers.get(id), r[0], r[1]);
        Map<String, Object> m = Map.of("d", doc, "customerId", id, "fromIso", r[0].toString(), "toIso", r[1].toString());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(pdf.html("statement-document", m, true));
    }

    @GetMapping("/{id}/statement.pdf")
    public ResponseEntity<byte[]> statementPdf(@PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = range(from, to);
        Customer c = customers.get(id);
        byte[] bytes = pdf.pdf("statement-document", Map.of("d", documents.statement(c, r[0], r[1]), "customerId", id,
                "fromIso", r[0].toString(), "toIso", r[1].toString()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"statement-" + c.getCode() + ".pdf\"").body(bytes);
    }

    @GetMapping("/{id}/statement.csv")
    public ResponseEntity<byte[]> statementCsv(@PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = range(from, to);
        Customer c = customers.get(id);
        var doc = documents.statement(c, r[0], r[1]);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("", "Opening balance", "", "", "", "", doc.opening()));
        for (var l : doc.lines()) rows.add(List.of(l.date(), l.type(), l.ref() == null ? "" : l.ref(), l.description(), l.debit(), l.credit(), l.balance()));
        rows.add(List.of("", "Closing balance", "", "", "", "", doc.closing()));
        byte[] csv = Csv.write(List.of("Date", "Type", "Reference", "Description", "Debit", "Credit", "Balance"), rows);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"statement-" + c.getCode() + ".csv\"").body(csv);
    }
}
