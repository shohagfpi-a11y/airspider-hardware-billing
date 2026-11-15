package com.hardwarebilling.web;

import com.hardwarebilling.domain.Enums.PaymentMethod;
import com.hardwarebilling.domain.Payment;
import com.hardwarebilling.service.*;
import com.hardwarebilling.service.PaymentService.AllocationRequest;
import com.hardwarebilling.service.PaymentService.ReceiptRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class PaymentController {
    private final PaymentService payments;
    private final CustomerService customers;
    private final BankService banks;
    private final ReportService reports;
    private final CompanyService company;
    private final DocumentService documents;
    private final PdfService pdf;

    public PaymentController(PaymentService payments, CustomerService customers, BankService banks, ReportService reports,
                             CompanyService company, DocumentService documents, PdfService pdf) {
        this.payments = payments;
        this.customers = customers;
        this.banks = banks;
        this.reports = reports;
        this.company = company;
        this.documents = documents;
        this.pdf = pdf;
    }

    @GetMapping("/payments")
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) Long customerId, Model model) {
        model.addAttribute("rows", reports.receipts(from, to, customerId, 500));
        model.addAttribute("customers", customers.active());
        model.addAttribute("nav", "payments");
        return "payments";
    }

    @GetMapping("/payments/new")
    public String form(@RequestParam(required = false) Long customerId, @RequestParam(required = false) Long invoiceId, Model model) {
        model.addAttribute("customers", customers.active());
        model.addAttribute("customerId", customerId);
        model.addAttribute("invoiceId", invoiceId);
        model.addAttribute("open", customerId == null ? List.of() : reports.outstanding(customerId));
        model.addAttribute("banks", banks.active());
        model.addAttribute("methods", PaymentMethod.values());
        model.addAttribute("today", company.today());
        model.addAttribute("key", UUID.randomUUID().toString());
        model.addAttribute("nav", "payments");
        return "payment-form";
    }

    @PostMapping("/payments")
    public String create(@RequestParam Long customerId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDate,
                         @RequestParam BigDecimal amount, @RequestParam PaymentMethod method, @RequestParam(required = false) Long bankAccountId,
                         @RequestParam(required = false) String reference, @RequestParam(required = false) String notes,
                         @RequestParam(required = false) String idempotencyKey, @RequestParam Map<String, String> all,
                         RedirectAttributes ra) {
        List<AllocationRequest> allocs = new ArrayList<>();
        for (var e : all.entrySet()) {
            if (e.getKey().startsWith("alloc_") && e.getValue() != null && !e.getValue().isBlank()) {
                try {
                    allocs.add(new AllocationRequest(Long.parseLong(e.getKey().substring(6)), new BigDecimal(e.getValue().trim())));
                } catch (NumberFormatException ex) {
                    throw new BusinessException("Invalid allocation amount");
                }
            }
        }
        Payment p;
        try {
            p = payments.createReceipt(new ReceiptRequest(customerId, paymentDate, amount, method, bankAccountId, reference, notes, idempotencyKey, allocs));
        } catch (DataIntegrityViolationException dup) {
            // a concurrent double-click inserted the same idempotency key first: show that receipt
            p = payments.findByKey(idempotencyKey).orElseThrow(() -> dup);
        }
        ra.addFlashAttribute("success", "Receipt " + p.getReceiptNo() + " recorded");
        return "redirect:/payments/" + p.getId();
    }

    @GetMapping("/payments/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Payment p = payments.get(id);
        model.addAttribute("p", p);
        model.addAttribute("allocs", reports.allocationsForPayment(id));
        model.addAttribute("available", payments.availableCredit(p));
        model.addAttribute("open", reports.outstanding(p.getCustomer().getId()));
        model.addAttribute("methods", PaymentMethod.values());
        model.addAttribute("today", company.today());
        model.addAttribute("nav", "payments");
        return "payment-detail";
    }

    @GetMapping("/payments/{id}/receipt")
    public ResponseEntity<String> receiptHtml(@PathVariable Long id) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(pdf.html("receipt-document", Map.of("d", documents.receipt(id)), true));
    }

    @GetMapping("/payments/{id}/receipt.pdf")
    public ResponseEntity<byte[]> receiptPdf(@PathVariable Long id) {
        var d = documents.receipt(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.receiptNo().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf\"")
                .body(pdf.pdf("receipt-document", Map.of("d", d)));
    }

    @PostMapping("/payments/{id}/allocate")
    public String allocate(@PathVariable Long id, @RequestParam Long invoiceId, @RequestParam BigDecimal amount, RedirectAttributes ra) {
        payments.allocate(id, invoiceId, amount, null);
        ra.addFlashAttribute("success", "Credit applied to invoice");
        return "redirect:/payments/" + id;
    }

    @PostMapping("/payments/{id}/refund")
    public String refund(@PathVariable Long id, @RequestParam BigDecimal amount, @RequestParam PaymentMethod method,
                         @RequestParam(required = false) String reference, @RequestParam(required = false) String notes,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate refundDate, RedirectAttributes ra) {
        payments.refund(id, amount, refundDate, method, reference, notes);
        ra.addFlashAttribute("success", "Refund recorded");
        return "redirect:/payments/" + id;
    }

    @PostMapping("/payments/{id}/reverse")
    public String reverse(@PathVariable Long id, @RequestParam String reason, RedirectAttributes ra) {
        payments.reversePayment(id, reason);
        ra.addFlashAttribute("success", "Receipt reversed");
        return "redirect:/payments/" + id;
    }

    @PostMapping("/allocations/{id}/reverse")
    public String reverseAllocation(@PathVariable Long id, @RequestParam Long returnTo, @RequestParam String reason, @RequestParam(defaultValue = "payments") String area, RedirectAttributes ra) {
        payments.reverseAllocation(id, reason);
        ra.addFlashAttribute("success", "Allocation reversed");
        return "redirect:/" + ("invoices".equals(area) ? "invoices" : "payments") + "/" + returnTo;
    }
}
