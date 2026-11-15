package com.hardwarebilling.service;

import com.hardwarebilling.config.SecurityUtil;
import com.hardwarebilling.domain.AppUser;
import com.hardwarebilling.domain.Enums.Role;
import com.hardwarebilling.repo.AppUserRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class UserService {
    public static final int MIN_PASSWORD = 10;

    @Data
    public static class UserForm {
        private Long id;
        @NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "letters, digits, . _ - only") private String username;
        @NotBlank @Size(max = 120) private String fullName;
        @NotNull private Role role = Role.STAFF;
        private boolean active = true;
        /** Required for new users; optional when editing (blank = keep). */
        private String password;
    }

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(AppUserRepository repo, PasswordEncoder encoder, AuditService audit) {
        this.repo = repo;
        this.encoder = encoder;
        this.audit = audit;
    }

    public static void checkPassword(String p) {
        if (p == null || p.length() < MIN_PASSWORD) {
            throw new BusinessException("Password must be at least " + MIN_PASSWORD + " characters");
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<AppUser> all() { return repo.findAll(); }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public AppUser get(Long id) { return repo.findById(id).orElseThrow(() -> new BusinessException("User not found")); }

    /** Used by first-time setup only (no authentication exists yet). */
    @Transactional
    public AppUser createInitialAdmin(String username, String fullName, String password) {
        if (repo.count() > 0) throw new BusinessException("Setup is already complete");
        checkPassword(password);
        AppUser u = new AppUser();
        u.setUsername(username.trim());
        u.setFullName(fullName.trim());
        u.setRole(Role.ADMIN);
        u.setPasswordHash(encoder.encode(password));
        repo.save(u);
        audit.log("SETUP_ADMIN_CREATED", "AppUser", u.getId(), u.getUsername());
        return u;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AppUser save(UserForm f) {
        AppUser u;
        boolean isNew = f.getId() == null;
        if (isNew) {
            if (repo.existsByUsernameIgnoreCase(f.getUsername())) throw new BusinessException("Username already exists");
            checkPassword(f.getPassword());
            u = new AppUser();
            u.setUsername(f.getUsername().trim());
            u.setPasswordHash(encoder.encode(f.getPassword()));
        } else {
            u = get(f.getId());
            boolean losingAdmin = u.getRole() == Role.ADMIN && u.isActive() && (f.getRole() != Role.ADMIN || !f.isActive());
            if (losingAdmin && activeAdmins() <= 1) throw new BusinessException("At least one active administrator is required");
            if (f.getPassword() != null && !f.getPassword().isBlank()) {
                checkPassword(f.getPassword());
                u.setPasswordHash(encoder.encode(f.getPassword()));
            }
        }
        u.setFullName(f.getFullName().trim());
        u.setRole(f.getRole());
        u.setActive(isNew || f.isActive());
        repo.save(u);
        audit.log(isNew ? "USER_CREATE" : "USER_UPDATE", "AppUser", u.getId(), u.getUsername() + " role=" + u.getRole() + " active=" + u.isActive());
        return u;
    }

    @Transactional
    public void changeOwnPassword(String current, String next) {
        AppUser u = repo.findByUsernameIgnoreCase(SecurityUtil.username()).orElseThrow(() -> new BusinessException("User not found"));
        if (!encoder.matches(current, u.getPasswordHash())) throw new BusinessException("Current password is incorrect");
        checkPassword(next);
        u.setPasswordHash(encoder.encode(next));
        audit.log("PASSWORD_CHANGE", "AppUser", u.getId(), u.getUsername());
    }

    private long activeAdmins() {
        return repo.findAll().stream().filter(x -> x.isActive() && x.getRole() == Role.ADMIN).count();
    }
}
