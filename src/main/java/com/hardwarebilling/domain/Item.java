package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "item") @Getter @Setter
public class Item {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String code;
    @Enumerated(EnumType.STRING) @Column(name = "item_type") private Enums.ItemType itemType = Enums.ItemType.PRODUCT;
    private String name;
    private String barcode;
    private String serialNo;
    private String description;
    private String unit = "pcs";
    private BigDecimal defaultPrice = BigDecimal.ZERO;
    private BigDecimal taxRate = BigDecimal.ZERO;
    private boolean warrantyEnabled;
    private Integer warrantyDuration;
    @Enumerated(EnumType.STRING) private Enums.WarrantyUnit warrantyUnit;
    private String warrantyTerms;
    private boolean active = true;
    private Instant createdAt = Instant.now();
}
