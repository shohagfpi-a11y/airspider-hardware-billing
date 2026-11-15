package com.hardwarebilling.service;

import com.hardwarebilling.config.SecurityUtil;
import com.hardwarebilling.domain.*;
import com.hardwarebilling.domain.Enums.DocStatus;
import com.hardwarebilling.domain.Enums.PaymentStatus;
import com.hardwarebilling.domain.Enums.WarrantyUnit;
import com.hardwarebilling.repo.*;
import com.hardwarebilling.service.InvoiceCalculator.LineInput;
import com.hardwarebilling.service.InvoiceCalculator.Totals;
import com.hardwarebilling.service.InvoiceRequests.InvoiceRequest;
import com.hardwarebilling.service.InvoiceRequests.LineRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final BankAccountRepository banks;
    private final ItemRepository items;
    private final PaymentAllocationRepository allocations;
    private final CompanyService company;
    private final NumberingService numbering;
    private final AuditService audit;
    private final JsonMapper json;

    public InvoiceService(InvoiceRepository invoices, CustomerRepository customers, BankAccountRepository banks,
                          ItemRepository items, PaymentAllocationRepository allocations, CompanyService company,
                          NumberingService numbering, AuditService audit, JsonMapper json) {
        this.invoices = invoices;
        this.customers = customers;
        this.banks = banks;
        this.items = items;
        this.allocations = allocations;
        this.company = company;
        this.numbering = numbering;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Invoice get(Long id) {
        Invoice i = invoices.findById(id).orElseThrow(() -> new BusinessException("Invoice not found"));
        i.getLines().size();
        return i;
    }

    @Transactional(readOnly = true)
    public Invoice findByNumber(String number) {
        return invoices.findByInvoiceNumberIgnoreCase(number.trim())
                .orElseThrow(() -> new BusinessException("No invoice found with number " + number));
    }

    /** Amount currently allocated to the invoice (active allocations only). */
    @Transactional(readOnly = true)
    public BigDecimal allocated(Long invoiceId) {
        return allocations.activeSumByInvoice(invoiceId);
    }

    public static BigDecimal balance(Invoice i, BigDecimal allocated) {
        return i.getDocStatus() == DocStatus.ISSUED ? i.getGrandTotal().subtract(allocated) : BigDecimal.ZERO;
    }

    public static PaymentStatus paymentStatus(Invoice i, BigDecimal allocated) {
        if (i.getDocStatus() != DocStatus.ISSUED) return PaymentStatus.UNPAID;
        if (allocated.signum() <= 0) return PaymentStatus.UNPAID;
        return allocated.compareTo(i.getGrandTotal()) >= 0 ? PaymentStatus.PAID : PaymentStatus.PARTIALLY_PAID;
    }

    /** Overdue is derived: issued, unpaid balance, and due date has passed. */
    public static boolean overdue(Invoice i, BigDecimal allocated, LocalDate today) {
        return i.getDocStatus() == DocStatus.ISSUED && balance(i, allocated).signum() > 0 && i.getDueDate().isBefore(today);
    }

    // ------------------------------------------------------------------ draft save

    /** Server-side calculation for the live preview in the grid (same code path as save/issue). */
    @Transactional(readOnly = true)
    public Totals preview(List<LineRequest> lines) {
        int scale = Money.scaleFor(company.get().getCurrency());
        return InvoiceCalculator.calculate(toInputs(lines == null ? List.of() : lines), scale);
    }

    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public Invoice saveDraft(InvoiceRequest r) {
        CompanyProfile co = company.get();
        Invoice inv;
        boolean isNew = r.id() == null;
        if (isNew) {
            inv = new Invoice();
            inv.setCreatedBy(SecurityUtil.username());
        } else {
            inv = invoices.lockById(r.id()).orElseThrow(() -> new BusinessException("Invoice not found"));
            if (inv.getDocStatus() != DocStatus.DRAFT) throw new BusinessException("Only draft invoices can be edited");
            if (r.version() != null && !r.version().equals(inv.getVersion())) {
                throw new BusinessException("This draft was changed by someone else. Reload the page and try again.");
            }
        }
        if (r.customerId() == null) throw new BusinessException("Select a customer");
        Customer customer = customers.findById(r.customerId()).orElseThrow(() -> new BusinessException("Customer not found"));
        if (!customer.isActive() && (isNew || !customer.getId().equals(inv.getCustomer().getId()))) {
            throw new BusinessException("Customer is inactive");
        }
        if (r.invoiceDate() == null) throw new BusinessException("Invoice date is required");
        LocalDate due = r.dueDate() != null ? r.dueDate() : r.invoiceDate().plusDays(co.getDefaultPaymentTermsDays());
        if (due.isBefore(r.invoiceDate())) throw new BusinessException("Due date cannot be before the invoice date");
        if (r.lines() == null || r.lines().isEmpty()) throw new BusinessException("Add at least one line");

        BankAccount bank = null;
        if (r.bankAccountId() != null) {
            bank = banks.findById(r.bankAccountId()).orElseThrow(() -> new BusinessException("Bank account not found"));
            if (!bank.isActive()) throw new BusinessException("Selected bank account is inactive");
        }

        inv.setCustomer(customer);
        inv.setInvoiceDate(r.invoiceDate());
        inv.setDueDate(due);
        inv.setDeliveryNoteNo(ItemService.blankToNull(r.deliveryNoteNo()));
        inv.setLpoNo(ItemService.blankToNull(r.lpoNo()));
        inv.setDeliveryDate(r.deliveryDate());
        inv.setBankAccount(bank);
        inv.setCurrency(co.getCurrency());
        inv.setPaymentTerms(ItemService.blankToNull(r.paymentTerms()));
        inv.setNotes(ItemService.blankToNull(r.notes()));
        inv.setUpdatedAt(Instant.now());
        applyLines(inv, r.lines(), Money.scaleFor(co.getCurrency()));
        invoices.save(inv);
        audit.log(isNew ? "INVOICE_DRAFT_CREATE" : "INVOICE_DRAFT_UPDATE", "Invoice", inv.getId(),
                customer.getCode() + " total " + inv.getGrandTotal().toPlainString());
        return inv;
    }

    private List<LineInput> toInputs(List<LineRequest> lines) {
        List<LineInput> in = new ArrayList<>();
        for (LineRequest l : lines) in.add(new LineInput(l.quantity(), l.unitPrice(), l.discountPercent(), l.taxRate()));
        return in;
    }

    private void applyLines(Invoice inv, List<LineRequest> reqs, int scale) {
        int n = 0;
        for (LineRequest l : reqs) {
            n++;
            String where = "Line " + n + ": ";
            if (l.description() == null || l.description().isBlank()) throw new BusinessException(where + "description is required");
            if (l.quantity() == null || l.quantity().signum() <= 0) throw new BusinessException(where + "quantity must be greater than zero");
            if (l.quantity().scale() > 4 && l.quantity().stripTrailingZeros().scale() > 4) throw new BusinessException(where + "quantity allows at most 4 decimals");
            if (l.unitPrice() == null || l.unitPrice().signum() < 0) throw new BusinessException(where + "unit price cannot be negative");
            if (l.unitPrice().stripTrailingZeros().scale() > 6) throw new BusinessException(where + "unit price allows at most 6 decimals");
            BigDecimal disc = Money.nz(l.discountPercent());
            if (disc.signum() < 0 || disc.compareTo(Money.HUNDRED) > 0) throw new BusinessException(where + "discount must be between 0 and 100%");
            if (Money.nz(l.taxRate()).signum() < 0) throw new BusinessException(where + "tax rate cannot be negative");
            if (l.warrantyEnabled() != null && l.warrantyEnabled()
                    && (l.warrantyDuration() == null || l.warrantyDuration() <= 0 || parseUnit(l.warrantyUnit()) == null)) {
                throw new BusinessException(where + "warranty needs a duration and unit");
            }
        }
        // rebuild all lines (orphanRemoval deletes the old rows)
        inv.getLines().clear();
        Totals t = InvoiceCalculator.calculate(toInputs(reqs), scale);
        LocalDate wStart = inv.getDeliveryDate() != null ? inv.getDeliveryDate() : inv.getInvoiceDate();
        for (int i = 0; i < reqs.size(); i++) {
            LineRequest l = reqs.get(i);
            var res = t.lines().get(i);
            InvoiceLine line = new InvoiceLine();
            line.setInvoice(inv);
            line.setLineNo(i + 1);
            if (l.itemId() != null) {
                Item it = items.findById(l.itemId()).orElse(null);
                if (it != null) { line.setItemId(it.getId()); line.setItemCode(it.getCode()); }
            }
            line.setSerialNo(ItemService.blankToNull(l.serialNo()));
            line.setDescription(l.description().trim());
            line.setQuantity(l.quantity());
            line.setUnit(ItemService.blankToNull(l.unit()));
            line.setUnitPrice(l.unitPrice());
            line.setDiscountPercent(Money.nz(l.discountPercent()));
            line.setTaxRate(Money.nz(l.taxRate()));
            line.setGrossAmount(res.gross());
            line.setDiscountAmount(res.discount());
            line.setNetAmount(res.net());
            line.setTaxAmount(res.tax());
            line.setLineTotal(res.total());
            boolean w = Boolean.TRUE.equals(l.warrantyEnabled());
            line.setWarrantyEnabled(w);
            if (w) {
                WarrantyUnit u = parseUnit(l.warrantyUnit());
                line.setWarrantyDuration(l.warrantyDuration());
                line.setWarrantyUnit(u.name());
                line.setWarrantyTerms(ItemService.blankToNull(l.warrantyTerms()));
                line.setWarrantyStart(wStart);
                line.setWarrantyEnd(warrantyEnd(wStart, l.warrantyDuration(), u));
            }
            inv.getLines().add(line);
        }
        inv.setGrossTotal(t.gross());
        inv.setDiscountTotal(t.discount());
        inv.setNetTotal(t.net());
        inv.setTaxTotal(t.tax());
        inv.setGrandTotal(t.grand());
    }

    private static WarrantyUnit parseUnit(String s) {
        try { return s == null ? null : WarrantyUnit.valueOf(s.toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }

    public static LocalDate warrantyEnd(LocalDate start, int duration, WarrantyUnit unit) {
        return switch (unit) {
            case DAYS -> start.plusDays(duration);
            case MONTHS -> start.plusMonths(duration);
            case YEARS -> start.plusYears(duration);
        };
    }

    // ------------------------------------------------------------------ lifecycle

    /** Issues a draft: assigns the permanent number and freezes company/customer/bank snapshots. */
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public Invoice issue(Long id) {
        Invoice inv = invoices.lockById(id).orElseThrow(() -> new BusinessException("Invoice not found"));
        if (inv.getDocStatus() != DocStatus.DRAFT) throw new BusinessException("Only draft invoices can be issued");
        if (inv.getLines().isEmpty()) throw new BusinessException("Add at least one line before issuing");
        CompanyProfile co = company.get();
        if (!co.getCurrency().equals(inv.getCurrency())) throw new BusinessException("Company currency changed; reopen and save the draft again");
        if (inv.getGrandTotal().signum() <= 0) throw new BusinessException("Invoice total must be greater than zero");
        inv.setInvoiceNumber(co.getInvoicePrefix() + numbering.next("INVOICE"));
        inv.setCompanySnapshot(json.writeValueAsString(Snapshots.CompanySnap.of(co)));
        inv.setCustomerSnapshot(json.writeValueAsString(Snapshots.CustomerSnap.of(inv.getCustomer())));
        inv.setBankSnapshot(inv.getBankAccount() == null ? null : json.writeValueAsString(Snapshots.BankSnap.of(inv.getBankAccount())));
        inv.setDocStatus(DocStatus.ISSUED);
        inv.setIssuedAt(Instant.now());
        inv.setIssuedBy(SecurityUtil.username());
        inv.setUpdatedAt(Instant.now());
        audit.log("INVOICE_ISSUE", "Invoice", inv.getId(), inv.getInvoiceNumber() + " total " + inv.getGrandTotal().toPlainString());
        return inv;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Invoice voidInvoice(Long id, String reason) {
        if (reason == null || reason.isBlank()) throw new BusinessException("A reason is required to void an invoice");
        Invoice inv = invoices.lockById(id).orElseThrow(() -> new BusinessException("Invoice not found"));
        if (inv.getDocStatus() != DocStatus.ISSUED) throw new BusinessException("Only issued invoices can be voided");
        if (allocations.activeSumByInvoice(id).signum() > 0) {
            throw new BusinessException("Payments are allocated to this invoice. Reverse the allocations first, then void.");
        }
        inv.setDocStatus(DocStatus.VOIDED);
        inv.setVoidedAt(Instant.now());
        inv.setVoidedBy(SecurityUtil.username());
        inv.setVoidReason(reason.trim());
        inv.setUpdatedAt(Instant.now());
        audit.log("INVOICE_VOID", "Invoice", inv.getId(), inv.getInvoiceNumber() + " reason: " + reason.trim());
        return inv;
    }

    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public void deleteDraft(Long id) {
        Invoice inv = invoices.lockById(id).orElseThrow(() -> new BusinessException("Invoice not found"));
        if (inv.getDocStatus() != DocStatus.DRAFT) throw new BusinessException("Only drafts can be deleted; issued invoices must be voided");
        audit.log("INVOICE_DRAFT_DELETE", "Invoice", inv.getId(), "draft deleted");
        invoices.delete(inv);
    }

    public <T> T snapshot(String jsonText, Class<T> type) {
        return jsonText == null ? null : json.readValue(jsonText, type);
    }
}
