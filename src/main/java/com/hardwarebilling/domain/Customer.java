package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "customer") @Getter @Setter
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String code;
    private String name;
    private String contactPerson;
    private String mobile;
    private String phone;
    private String email;
    private String billingAddress;
    private String taxNumber;
    private Integer defaultPaymentTermsDays;
    private String notes;
    private boolean active = true;
    private Instant createdAt = Instant.now();
}
