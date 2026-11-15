package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Line snapshot: all values are copied onto the line, so later catalog edits never alter an invoice. */
@Entity @Table(name = "invoice_line") @Getter @Setter
public class InvoiceLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "invoice_id") private Invoice invoice;
    private int lineNo;
    private Long itemId;
    private String itemCode;
    private String serialNo;
    private String description;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal unitPrice;
    private BigDecimal discountPercent = BigDecimal.ZERO;
    private BigDecimal taxRate = BigDecimal.ZERO;
    private BigDecimal grossAmount;
    private BigDecimal discountAmount;
    private BigDecimal netAmount;
    private BigDecimal taxAmount;
    private BigDecimal lineTotal;
    private boolean warrantyEnabled;
    private Integer warrantyDuration;
    private String warrantyUnit;
    private String warrantyTerms;
    private LocalDate warrantyStart;
    private LocalDate warrantyEnd;
}
