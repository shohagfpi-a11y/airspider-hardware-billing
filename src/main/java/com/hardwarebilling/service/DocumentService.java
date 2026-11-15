package com.hardwarebilling.service;

import com.hardwarebilling.domain.*;
import com.hardwarebilling.domain.Enums.DocStatus;
import com.hardwarebilling.domain.Enums.PaymentStatus;
import com.hardwarebilling.domain.Enums.RecordStatus;
import com.hardwarebilling.repo.PaymentAllocationRepository;
import com.hardwarebilling.repo.RefundRepository;
import com.hardwarebilling.service.Snapshots.BankSnap;
import com.hardwarebilling.service.Snapshots.CompanySnap;
import com.hardwarebilling.service.Snapshots.CustomerSnap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the view models for the printable documents. The browser print view and the PDF are both
 * rendered from these objects with the same template, so their numbers can never differ.
 * Issued invoices use the frozen snapshots; drafts use current master data.
 */
@Service
@Transactional(readOnly = true)
public class DocumentService {

    public record LineView(int no, String code, String description, String serial, String qty, String unit, String price,
                           String discount, String taxRate, String net, String tax, String total, String warranty) {}

    public record PaymentView(String receiptNo, String date, String label, String amount) {}

    public record InvoiceDoc(Long id, String number, String title, String status, boolean draft, boolean voided,
                             String voidInfo, CompanySnap company, CustomerSnap customer, BankSnap bank,
                             String logo, String stamp, String signature, String barcode,
                             String invoiceDate, String dueDate, String deliveryNote, String lpo, String deliveryDate,
                             String paymentTerms, String notes, String currency, String taxLabel, List<LineView> lines,
                             boolean hasDiscount, boolean hasWarranty, boolean hasSerial,
                             String gross, String discount, String net, String tax, String grand,
                             List<PaymentView> payments, String paid, boolean hasAdvance, String advanceApplied, String balance,
                             String paymentStatus, boolean overdue, String asOf, String declaration, String footer,
                             String amountInWords, String footerNote) {}

    public record AllocView(String invoiceNo, String date, String amount, String status) {}

    public record ReceiptDoc(Long id, String receiptNo, CompanySnap company, String logo, String customerName, String customerCode,
                             String customerTrn, String date, String amount, String method, String reference, String bank,
                             String notes, String currency, boolean reversed, String reverseInfo, List<AllocView> allocations,
                             String allocated, String refunded, String available, String asOf, String stamp, String signature,
                             String amountInWords) {}

    public record StatementLineView(String date, String type, String ref, String description, String debit, String credit,
                                    String applied, String balance) {}

    public record StatementDoc(CompanySnap company, String logo, String customerName, String customerCode, String customerAddress,
                               String customerTrn, String from, String to, String currency, String opening,
                               List<StatementLineView> lines, String closing, String outstanding, String credit,
                               String netPosition, String asOf) {}

    private final InvoiceService invoiceService;
    private final CompanyService company;
    private final FileStorageService files;
    private final BarcodeService barcode;
    private final PaymentAllocationRepository allocations;
    private final RefundRepository refunds;
    private final PaymentService paymentService;
    private final StatementService statements;
    private final ReportService reports;

    public DocumentService(InvoiceService invoiceService, CompanyService company, FileStorageService files,
                           BarcodeService barcode, PaymentAllocationRepository allocations, RefundRepository refunds,
                           PaymentService paymentService, StatementService statements, ReportService reports) {
        this.invoiceService = invoiceService;
        this.company = company;
        this.files = files;
        this.barcode = barcode;
        this.allocations = allocations;
        this.refunds = refunds;
        this.paymentService = paymentService;
        this.statements = statements;
        this.reports = reports;
    }

    public InvoiceDoc invoice(Long id) {
        Invoice inv = invoiceService.get(id);
        boolean draft = inv.getDocStatus() == DocStatus.DRAFT;
        CompanySnap co;
        CustomerSnap cu;
        BankSnap bank;
        if (draft) {
            co = CompanySnap.of(company.get());
            cu = CustomerSnap.of(inv.getCustomer());
            bank = BankSnap.of(inv.getBankAccount());
        } else {
            co = invoiceService.snapshot(inv.getCompanySnapshot(), CompanySnap.class);
            cu = invoiceService.snapshot(inv.getCustomerSnapshot(), CustomerSnap.class);
            bank = invoiceService.snapshot(inv.getBankSnapshot(), BankSnap.class);
        }
        int scale = Money.scaleFor(inv.getCurrency());
        LocalDate today = company.today();

        List<LineView> lines = new ArrayList<>();
        boolean hasDiscount = false, hasWarranty = false, hasSerial = false;
        for (InvoiceLine l : inv.getLines()) {
            String w = null;
            if (l.isWarrantyEnabled() && l.getWarrantyDuration() != null && l.getWarrantyUnit() != null) {
                hasWarranty = true;
                String unitWord = l.getWarrantyUnit().toLowerCase();
                if (l.getWarrantyDuration() == 1 && unitWord.endsWith("s")) unitWord = unitWord.substring(0, unitWord.length() - 1);
                w = "Warranty: " + l.getWarrantyDuration() + " " + unitWord
                        + " (" + Fmt.date(l.getWarrantyStart()) + " to " + Fmt.date(l.getWarrantyEnd()) + ")"
                        + (l.getWarrantyTerms() != null ? " - " + l.getWarrantyTerms() : "");
            }
            if (l.getDiscountPercent().signum() > 0) hasDiscount = true;
            if (l.getSerialNo() != null) hasSerial = true;
            lines.add(new LineView(l.getLineNo(), l.getItemCode(), l.getDescription(), l.getSerialNo(), Fmt.qty(l.getQuantity()),
                    l.getUnit(), Fmt.price(l.getUnitPrice(), scale),
                    l.getDiscountPercent().signum() > 0 ? Fmt.rate(l.getDiscountPercent()) : "",
                    Fmt.rate(l.getTaxRate()), Fmt.money(l.getNetAmount(), scale), Fmt.money(l.getTaxAmount(), scale),
                    Fmt.money(l.getLineTotal(), scale), w));
        }

        // payments applied, "as of" today: the commercial data above is frozen, only this block moves
        List<PaymentView> pays = new ArrayList<>();
        BigDecimal paid = BigDecimal.ZERO, advance = BigDecimal.ZERO;
        if (inv.getDocStatus() == DocStatus.ISSUED) {
            for (PaymentAllocation a : allocations.findByInvoiceIdAndStatus(inv.getId(), RecordStatus.ACTIVE)) {
                Payment p = a.getPayment();
                boolean adv = p.getPaymentDate().isBefore(inv.getInvoiceDate());
                paid = paid.add(a.getAmount());
                if (adv) advance = advance.add(a.getAmount());
                pays.add(new PaymentView(p.getReceiptNo(), Fmt.date(a.getAllocationDate()),
                        adv ? "Advance" : "Payment", Fmt.money(a.getAmount(), scale)));
            }
        }
        BigDecimal balance = InvoiceService.balance(inv, paid);
        PaymentStatus ps = InvoiceService.paymentStatus(inv, paid);
        boolean overdue = InvoiceService.overdue(inv, paid, today);
        String number = draft ? "DRAFT" : inv.getInvoiceNumber();
        String status = draft ? "DRAFT" : inv.getDocStatus() == DocStatus.VOIDED ? "VOIDED" : "ISSUED";
        String voidInfo = inv.getDocStatus() == DocStatus.VOIDED ? "Voided - " + inv.getVoidReason() : null;
        // single-line page footer for the PDF/print margin box (quotes and line breaks removed: it goes into a CSS string)
        String footer = java.util.stream.Stream.of(co.name(), co.phone() == null ? null : "Tel " + co.phone(), co.mobile(), co.email(), co.website())
                .filter(x -> x != null && !x.isBlank()).collect(java.util.stream.Collectors.joining("  |  "));
        footer = Fmt.cssString(footer);
        boolean hasTax = co.taxNumber() != null && !co.taxNumber().isBlank();

        return new InvoiceDoc(inv.getId(), number, hasTax ? "TAX INVOICE" : "INVOICE", status, draft,
                inv.getDocStatus() == DocStatus.VOIDED, voidInfo, co, cu, bank,
                files.dataUri(co.logoFile()), files.dataUri(co.stampFile()), files.dataUri(co.signatureFile()),
                draft ? null : barcode.code128DataUri(inv.getInvoiceNumber()),
                Fmt.date(inv.getInvoiceDate()), Fmt.date(inv.getDueDate()), inv.getDeliveryNoteNo(), inv.getLpoNo(),
                Fmt.date(inv.getDeliveryDate()), inv.getPaymentTerms(), inv.getNotes(), inv.getCurrency(), co.taxLabel(), lines,
                hasDiscount, hasWarranty, hasSerial,
                Fmt.money(inv.getGrossTotal(), scale), Fmt.money(inv.getDiscountTotal(), scale), Fmt.money(inv.getNetTotal(), scale),
                Fmt.money(inv.getTaxTotal(), scale), Fmt.money(inv.getGrandTotal(), scale),
                pays, Fmt.money(paid, scale), advance.signum() > 0, Fmt.money(advance, scale), Fmt.money(balance, scale),
                ps.name(), overdue, Fmt.date(today), co.declaration(), footer,
                AmountInWords.of(inv.getGrandTotal(), inv.getCurrency()), co.footerNote());
    }

    public ReceiptDoc receipt(Long id) {
        Payment p = paymentService.get(id);
        CompanyProfile co = company.get();
        int scale = Money.scaleFor(co.getCurrency());
        List<AllocView> allocs = new ArrayList<>();
        for (PaymentAllocation a : allocations.findByPaymentIdOrderByIdAsc(p.getId())) {
            allocs.add(new AllocView(a.getInvoice().getInvoiceNumber(), Fmt.date(a.getAllocationDate()),
                    Fmt.money(a.getAmount(), scale), a.getStatus() == RecordStatus.ACTIVE ? "Active" : "Reversed"));
        }
        BigDecimal allocated = allocations.activeSumByPayment(p.getId());
        BigDecimal refunded = refunds.sumByPayment(p.getId());
        BigDecimal available = paymentService.availableCredit(p);
        boolean reversed = p.getStatus() == RecordStatus.REVERSED;
        return new ReceiptDoc(p.getId(), p.getReceiptNo(), CompanySnap.of(co), files.dataUri(co.getLogoFile()),
                p.getCustomer().getName(), p.getCustomer().getCode(), p.getCustomer().getTaxNumber(),
                Fmt.date(p.getPaymentDate()), Fmt.money(p.getAmount(), scale), p.getMethod().getLabel(), p.getReference(),
                p.getBankLabel(), p.getNotes(), co.getCurrency(), reversed,
                reversed ? "Reversed - " + p.getReverseReason() : null, allocs, Fmt.money(allocated, scale),
                Fmt.money(refunded, scale), Fmt.money(available, scale), Fmt.date(company.today()),
                files.dataUri(co.getStampFile()), files.dataUri(co.getSignatureFile()),
                AmountInWords.of(p.getAmount(), co.getCurrency()));
    }

    public StatementDoc statement(Customer c, LocalDate from, LocalDate to) {
        CompanyProfile co = company.get();
        int scale = Money.scaleFor(co.getCurrency());
        var st = statements.statement(c.getId(), from, to);
        List<StatementLineView> lines = new ArrayList<>();
        for (var l : st.lines()) {
            lines.add(new StatementLineView(Fmt.date(l.date()), l.type(), l.ref(), l.description(),
                    l.debit() == null ? "" : Fmt.money(l.debit(), scale), l.credit() == null ? "" : Fmt.money(l.credit(), scale),
                    l.applied() == null ? "" : Fmt.money(l.applied(), scale), Fmt.money(l.balance(), scale)));
        }
        var pos = reports.customerPosition(c.getId());
        BigDecimal net = pos.outstanding().subtract(pos.credit());
        return new StatementDoc(CompanySnap.of(co), files.dataUri(co.getLogoFile()), c.getName(), c.getCode(), c.getBillingAddress(),
                c.getTaxNumber(), Fmt.date(from), Fmt.date(to), co.getCurrency(), Fmt.money(st.opening(), scale), lines,
                Fmt.money(st.closing(), scale), Fmt.money(pos.outstanding(), scale), Fmt.money(pos.credit(), scale),
                Fmt.money(net, scale), Fmt.date(company.today()));
    }
}
