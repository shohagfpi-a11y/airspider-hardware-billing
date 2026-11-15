package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "invoice") @Getter @Setter
public class Invoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private Long version;
    private String invoiceNumber;
    @Enumerated(EnumType.STRING) @Column(name = "doc_status") private Enums.DocStatus docStatus = Enums.DocStatus.DRAFT;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "customer_id") private Customer customer;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private String deliveryNoteNo;
    private String lpoNo;
    private LocalDate deliveryDate;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "bank_account_id") private BankAccount bankAccount;
    private String currency;
    private String paymentTerms;
    private String notes;
    private BigDecimal grossTotal = BigDecimal.ZERO;
    private BigDecimal discountTotal = BigDecimal.ZERO;
    private BigDecimal netTotal = BigDecimal.ZERO;
    private BigDecimal taxTotal = BigDecimal.ZERO;
    private BigDecimal grandTotal = BigDecimal.ZERO;
    @Column(columnDefinition = "text") private String companySnapshot;
    @Column(columnDefinition = "text") private String customerSnapshot;
    @Column(columnDefinition = "text") private String bankSnapshot;
    private Instant issuedAt;
    private String issuedBy;
    private Instant voidedAt;
    private String voidedBy;
    private String voidReason;
    private String createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<InvoiceLine> lines = new ArrayList<>();

    @Transient public boolean isDraft() { return docStatus == Enums.DocStatus.DRAFT; }
}
