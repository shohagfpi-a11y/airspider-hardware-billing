package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "company_profile") @Getter @Setter
public class CompanyProfile {
    @Id private Long id = 1L;
    private String legalName;
    private String tradingName;
    private String address;
    private String city;
    private String country;
    private String phone;
    private String mobile;
    private String email;
    private String website;
    private String taxNumber;
    private String currency = "AED";
    private String timezone = "Asia/Dubai";
    private String invoicePrefix = "INV-";
    private String receiptPrefix = "RC-";
    private int defaultPaymentTermsDays = 30;
    private String defaultPaymentTerms;
    private String defaultNotes;
    private String declarationText;
    private String invoiceFooterNote = DEFAULT_FOOTER_NOTE;

    public static final String DEFAULT_FOOTER_NOTE = "Please check this invoice on receipt. If you find any issue, inform us within 3 days; "
            + "after that the invoice is considered accepted and cannot be changed.";
    private String taxLabel = "VAT";
    private BigDecimal defaultTaxRate = new BigDecimal("5");
    private String logoFile;
    private String stampFile;
    private String signatureFile;
    private Instant updatedAt = Instant.now();

    /** Name printed on documents: trading name if set, else legal name. */
    @Transient public String getDisplayName() {
        return tradingName != null && !tradingName.isBlank() ? tradingName : legalName;
    }
}
