package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "payment_allocation") @Getter @Setter
public class PaymentAllocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_id") private Payment payment;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "invoice_id") private Invoice invoice;
    private BigDecimal amount;
    private LocalDate allocationDate;
    @Enumerated(EnumType.STRING) private Enums.RecordStatus status = Enums.RecordStatus.ACTIVE;
    private Instant reversedAt;
    private String reversedBy;
    private String reverseReason;
    private String createdBy;
    private Instant createdAt = Instant.now();
}
