package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "payment") @Getter @Setter
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String receiptNo;
    @ManyToOne(fetch = FetchType.EAGER, optional = false) @JoinColumn(name = "customer_id") private Customer customer;
    private LocalDate paymentDate;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING) private Enums.PaymentMethod method;
    private Long bankAccountId;
    private String bankLabel;
    private String reference;
    private String notes;
    @Enumerated(EnumType.STRING) private Enums.RecordStatus status = Enums.RecordStatus.ACTIVE;
    private String idempotencyKey;
    private Instant reversedAt;
    private String reversedBy;
    private String reverseReason;
    private String createdBy;
    private Instant createdAt = Instant.now();
}
