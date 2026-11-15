package com.hardwarebilling.web;

import com.hardwarebilling.domain.Enums.DocStatus;
import com.hardwarebilling.domain.Enums.PaymentStatus;
import com.hardwarebilling.domain.Invoice;
import com.hardwarebilling.domain.InvoiceLine;
import com.hardwarebilling.service.*;
import com.hardwarebilling.service.InvoiceRequests.InvoiceRequest;
import com.hardwarebilling.service.InvoiceRequests.LineRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/invoices")
public class InvoiceController {
    private final InvoiceService invoices;
    private final CustomerService customers;
    private final BankService banks;
    private final CompanyService company;
    private final ReportService reports;
    private final DocumentService documents;
    private final PdfService pdf;
    private final PaymentService payments;
    private final JsonMapper json;

    public InvoiceController(InvoiceService invoices, CustomerService customers, BankService banks, CompanyService company,
                             ReportService reports, DocumentService documents, PdfService pdf, PaymentService payments, JsonMapper json) {
        this.invoices = invoices;
        this.customers = customers;
        this.banks = banks;
        this.company = company;
        this.reports = reports;
        this.documents = documents;
        this.pdf = pdf;
        this.payments = payments;
        this.json = json;
    }

    @GetMapping
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) Long customerId,
                       @RequestParam(required = false) DocStatus status,
                       @RequestParam(required = false) PaymentStatus paymentStatus,
                       @RequestParam(required = false, defaultValue = "false") boolean overdue,
                       @RequestParam(required = false) String q, Model model) {
        model.addAttribute("rows", reports.invoices(new ReportService.InvoiceFilter(from, to, customerId, status, paymentStatus, overdue, q), 500));
        model.addAttribute("customers", customers.active());
        model.addAttribute("nav", "invoices");
        return "invoices";
    }

    @GetMapping("/lookup")
    public String lookup(@RequestParam String number) {
        return "redirect:/invoices/" + invoices.findByNumber(number).getId();
    }

    @GetMapping("/new")
    public String create(@RequestParam(required = false) Long customerId, Model model) {
        return editor(null, customerId, model);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        Invoice inv = invoices.get(id);
        if (!inv.isDraft()) throw new BusinessException("Issued invoices cannot be edited. Void and re-issue instead.");
        return editor(inv, null, model);
    }

    private String editor(Invoice inv, Long customerId, Model model) {
        var co = company.get();
        InvoiceRequest initial;
        if (inv == null) {
            Long bankId = null;   // new invoices start as Cash; choosing Bank pre-selects the default / only account in the browser
            LocalDate today = company.today();
            int days = co.getDefaultPaymentTermsDays();
            if (customerId != null) {
                var c = customers.get(customerId);
                if (c.getDefaultPaymentTermsDays() != null) days = c.getDefaultPaymentTermsDays();
            }
            initial = new InvoiceRequest(null, null, customerId, today, today.plusDays(days), null, null, null, bankId,
                    co.getDefaultPaymentTerms(), co.getDefaultNotes(), List.of());
        } else {
            List<LineRequest> lines = new ArrayList<>();
            for (InvoiceLine l : inv.getLines()) {
                lines.add(new LineRequest(l.getItemId(), l.getItemCode(), l.getSerialNo(), l.getDescription(), l.getQuantity(), l.getUnit(),
                        l.getUnitPrice(), l.getDiscountPercent(), l.getTaxRate(), l.isWarrantyEnabled(), l.getWarrantyDuration(),
                        l.getWarrantyUnit(), l.getWarrantyTerms()));
            }
            initial = new InvoiceRequest(inv.getId(), inv.getVersion(), inv.getCustomer().getId(), inv.getInvoiceDate(), inv.getDueDate(),
                    inv.getDeliveryNoteNo(), inv.getLpoNo(), inv.getDeliveryDate(),
                    inv.getBankAccount() == null ? null : inv.getBankAccount().getId(), inv.getPaymentTerms(), inv.getNotes(), lines);
        }
        model.addAttribute("initialJson", json.writeValueAsString(initial));
        model.addAttribute("settingsJson", json.writeValueAsString(Map.of(
                "defaultTaxRate", co.getDefaultTaxRate(), "taxLabel", co.getTaxLabel(), "currency", co.getCurrency(),
                "scale", Money.scaleFor(co.getCurrency()), "termsDays", co.getDefaultPaymentTermsDays())));
        model.addAttribute("customers", customers.active());
        model.addAttribute("banks", banks.active());
        model.addAttribute("co", co);
        model.addAttribute("number", inv == null ? null : (inv.getInvoiceNumber() == null ? "Draft #" + inv.getId() : inv.getInvoiceNumber()));
        model.addAttribute("nav", "invoices");
        return "invoice-form";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Invoice inv = invoices.get(id);
        var doc = documents.invoice(id);
        model.addAttribute("inv", inv);
        model.addAttribute("d", doc);
        model.addAttribute("creditReceipts", reports.receiptsWithCredit(inv.getCustomer().getId()));
        model.addAttribute("allocs", allocationRows(id));
        model.addAttribute("nav", "invoices");
        return "invoice-detail";
    }

    private List<?> allocationRows(Long invoiceId) {
        return reports.allocationsForInvoice(invoiceId);
    }

    @GetMapping("/{id}/print")
    public ResponseEntity<String> print(@PathVariable Long id) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(pdf.html("invoice-document", Map.of("d", documents.invoice(id)), true));
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        var doc = documents.invoice(id);
        byte[] bytes = pdf.pdf("invoice-document", Map.of("d", doc));
        String name = doc.draft() ? "draft-" + id : doc.number();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf\"")
                .body(bytes);
    }

    @PostMapping("/{id}/issue")
    public String issue(@PathVariable Long id, RedirectAttributes ra) {
        Invoice inv = invoices.issue(id);
        ra.addFlashAttribute("success", "Invoice " + inv.getInvoiceNumber() + " issued");
        return "redirect:/invoices/" + id;
    }

    @PostMapping("/{id}/void")
    public String voidInvoice(@PathVariable Long id, @RequestParam String reason, RedirectAttributes ra) {
        invoices.voidInvoice(id, reason);
        ra.addFlashAttribute("success", "Invoice voided");
        return "redirect:/invoices/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        invoices.deleteDraft(id);
        ra.addFlashAttribute("success", "Draft deleted");
        return "redirect:/invoices";
    }

    @PostMapping("/{id}/apply-credit")
    public String applyCredit(@PathVariable Long id, @RequestParam Long paymentId, @RequestParam BigDecimal amount, RedirectAttributes ra) {
        payments.allocate(paymentId, id, amount, null);
        ra.addFlashAttribute("success", "Credit applied");
        return "redirect:/invoices/" + id;
    }
}
