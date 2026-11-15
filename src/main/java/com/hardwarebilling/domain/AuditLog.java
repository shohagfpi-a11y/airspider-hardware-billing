package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "audit_log") @Getter @Setter
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Instant at = Instant.now();
    private String username;
    private String action;
    private String entityType;
    private String entityId;
    private String details;
}
