package com.hardwarebilling.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "app_user") @Getter @Setter
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String username;
    private String passwordHash;
    private String fullName;
    @Enumerated(EnumType.STRING) private Enums.Role role;
    private boolean active = true;
    private Instant createdAt = Instant.now();
}
