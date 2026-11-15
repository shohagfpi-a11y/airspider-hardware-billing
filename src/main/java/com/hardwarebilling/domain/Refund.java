package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "refund") @Getter @Setter
public class Refund {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "payment_id") private Payment payment;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "customer_id") private Customer customer;
    private BigDecimal amount;
    private LocalDate refundDate;
    @Enumerated(EnumType.STRING) private Enums.PaymentMethod method;
    private String reference;
    private String notes;
    private String createdBy;
    private Instant createdAt = Instant.now();
}
