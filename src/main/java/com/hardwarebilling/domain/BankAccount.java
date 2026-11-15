package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "bank_account") @Getter @Setter
public class BankAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String bankName;
    private String accountHolder;
    private String accountNumber;
    private String iban;
    private String swift;
    private String branch;
    private String currency = "AED";
    private boolean active = true;
    @Column(name = "is_default") private boolean defaultAccount;

    @Transient public String getLabel() {
        return bankName + (iban != null && !iban.isBlank() ? " - " + iban : "");
    }
}
