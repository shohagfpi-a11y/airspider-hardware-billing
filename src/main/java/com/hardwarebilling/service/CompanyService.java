package com.hardwarebilling.service;

import com.hardwarebilling.domain.CompanyProfile;
import com.hardwarebilling.repo.CompanyProfileRepository;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Currency;

@Service
public class CompanyService {

    @Data
    public static class CompanyForm {
        @NotBlank @Size(max = 200) private String legalName;
        @Size(max = 200) private String tradingName;
        @Size(max = 400) private String address;
        @Size(max = 100) private String city;
        @Size(max = 100) private String country;
        @Size(max = 50) private String phone;
        @Size(max = 50) private String mobile;
        @Email @Size(max = 150) private String email;
        @Size(max = 150) private String website;
        @Size(max = 50) private String taxNumber;
        @NotBlank @Size(min = 3, max = 3) private String currency = "AED";
        @NotBlank private String timezone = "Asia/Dubai";
        @NotBlank @Size(max = 20) private String invoicePrefix = "INV-";
        @NotBlank @Size(max = 20) private String receiptPrefix = "RC-";
        @Min(1) private Long nextInvoiceNumber;
        @NotNull @Min(0) @Max(365) private Integer defaultPaymentTermsDays = 30;
        @Size(max = 500) private String defaultPaymentTerms;
        @Size(max = 1000) private String defaultNotes;
        @Size(max = 1000) private String declarationText;
        @Size(max = 500) private String invoiceFooterNote;
        @NotBlank @Size(max = 30) private String taxLabel = "VAT";
        @NotNull @DecimalMin("0") @DecimalMax("100") private BigDecimal defaultTaxRate = new BigDecimal("5");
        private boolean removeStamp;
        private boolean removeSignature;
    }

    private final CompanyProfileRepository repo;
    private final FileStorageService files;
    private final NumberingService numbering;
    private final AuditService audit;

    public CompanyService(CompanyProfileRepository repo, FileStorageService files, NumberingService numbering, AuditService audit) {
        this.repo = repo;
        this.files = files;
        this.numbering = numbering;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public CompanyProfile get() {
        return repo.findById(1L).orElseThrow(() -> new BusinessException("Company profile has not been set up yet"));
    }

    /** "Today" in the company's timezone (used for overdue and "as of" dates). */
    @Transactional(readOnly = true)
    public java.time.LocalDate today() {
        return java.time.LocalDate.now(ZoneId.of(get().getTimezone()));
    }

    @Transactional(readOnly = true)
    public boolean exists() {
        return repo.existsById(1L);
    }

    public CompanyForm toForm(CompanyProfile c) {
        CompanyForm f = new CompanyForm();
        f.setLegalName(c.getLegalName()); f.setTradingName(c.getTradingName()); f.setAddress(c.getAddress());
        f.setCity(c.getCity()); f.setCountry(c.getCountry()); f.setPhone(c.getPhone()); f.setMobile(c.getMobile());
        f.setEmail(c.getEmail()); f.setWebsite(c.getWebsite()); f.setTaxNumber(c.getTaxNumber());
        f.setCurrency(c.getCurrency()); f.setTimezone(c.getTimezone()); f.setInvoicePrefix(c.getInvoicePrefix());
        f.setReceiptPrefix(c.getReceiptPrefix()); f.setNextInvoiceNumber(numbering.peek("INVOICE"));
        f.setDefaultPaymentTermsDays(c.getDefaultPaymentTermsDays()); f.setDefaultPaymentTerms(c.getDefaultPaymentTerms());
        f.setDefaultNotes(c.getDefaultNotes()); f.setDeclarationText(c.getDeclarationText()); f.setInvoiceFooterNote(c.getInvoiceFooterNote());
        f.setTaxLabel(c.getTaxLabel()); f.setDefaultTaxRate(c.getDefaultTaxRate());
        return f;
    }

    /** Creates the single company row during first-time setup. */
    @Transactional
    public CompanyProfile create(CompanyForm f) {
        CompanyProfile c = new CompanyProfile();
        apply(c, f);
        return repo.save(c);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CompanyProfile update(CompanyForm f, MultipartFile logo, MultipartFile stamp, MultipartFile signature) {
        CompanyProfile c = get();
        apply(c, f);
        String l = files.store(logo);
        if (l != null) c.setLogoFile(l);
        String s = files.store(stamp);
        if (s != null) c.setStampFile(s);
        else if (f.isRemoveStamp()) c.setStampFile(null);
        String g = files.store(signature);
        if (g != null) c.setSignatureFile(g);
        else if (f.isRemoveSignature()) c.setSignatureFile(null);
        if (f.getNextInvoiceNumber() != null && f.getNextInvoiceNumber() != numbering.peek("INVOICE")) {
            numbering.raiseTo("INVOICE", f.getNextInvoiceNumber());
        }
        audit.log("COMPANY_UPDATE", "CompanyProfile", 1, "Company settings changed");
        return c;
    }

    private void apply(CompanyProfile c, CompanyForm f) {
        try {
            Currency.getInstance(f.getCurrency().trim().toUpperCase());
            ZoneId.of(f.getTimezone().trim());
        } catch (Exception e) {
            throw new BusinessException("Invalid currency code or timezone");
        }
        c.setLegalName(f.getLegalName().trim());
        c.setTradingName(ItemService.blankToNull(f.getTradingName()));
        c.setAddress(ItemService.blankToNull(f.getAddress()));
        c.setCity(ItemService.blankToNull(f.getCity()));
        c.setCountry(ItemService.blankToNull(f.getCountry()));
        c.setPhone(ItemService.blankToNull(f.getPhone()));
        c.setMobile(ItemService.blankToNull(f.getMobile()));
        c.setEmail(ItemService.blankToNull(f.getEmail()));
        c.setWebsite(ItemService.blankToNull(f.getWebsite()));
        c.setTaxNumber(ItemService.blankToNull(f.getTaxNumber()));
        c.setCurrency(f.getCurrency().trim().toUpperCase());
        c.setTimezone(f.getTimezone().trim());
        c.setInvoicePrefix(f.getInvoicePrefix().trim());
        c.setReceiptPrefix(f.getReceiptPrefix().trim());
        c.setDefaultPaymentTermsDays(f.getDefaultPaymentTermsDays());
        c.setDefaultPaymentTerms(ItemService.blankToNull(f.getDefaultPaymentTerms()));
        c.setDefaultNotes(ItemService.blankToNull(f.getDefaultNotes()));
        c.setDeclarationText(ItemService.blankToNull(f.getDeclarationText()));
        if (f.getInvoiceFooterNote() != null) c.setInvoiceFooterNote(ItemService.blankToNull(f.getInvoiceFooterNote()));
        c.setTaxLabel(f.getTaxLabel().trim());
        c.setDefaultTaxRate(f.getDefaultTaxRate());
        c.setUpdatedAt(java.time.Instant.now());
    }
}
