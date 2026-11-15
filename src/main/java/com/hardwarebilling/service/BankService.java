package com.hardwarebilling.service;

import com.hardwarebilling.domain.BankAccount;
import com.hardwarebilling.repo.BankAccountRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class BankService {

    @Data
    public static class BankForm {
        private Long id;
        @NotBlank @Size(max = 120) private String bankName;
        @NotBlank @Size(max = 160) private String accountHolder;
        @Size(max = 60) private String accountNumber;
        @Size(max = 50) private String iban;
        @Size(max = 20) private String swift;
        @Size(max = 120) private String branch;
        @NotBlank @Size(min = 3, max = 3) private String currency = "AED";
        private boolean active = true;
        private boolean defaultAccount;
    }

    private final BankAccountRepository repo;
    private final AuditService audit;

    public BankService(BankAccountRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<BankAccount> all() { return repo.findAllByOrderByBankNameAsc(); }

    @Transactional(readOnly = true)
    public List<BankAccount> active() { return repo.findByActiveTrueOrderByBankNameAsc(); }

    @Transactional(readOnly = true)
    public BankAccount get(Long id) {
        return repo.findById(id).orElseThrow(() -> new BusinessException("Bank account not found"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public BankAccount save(BankForm f) {
        BankAccount b = f.getId() == null ? new BankAccount() : get(f.getId());
        b.setBankName(f.getBankName().trim());
        b.setAccountHolder(f.getAccountHolder().trim());
        b.setAccountNumber(ItemService.blankToNull(f.getAccountNumber()));
        b.setIban(ItemService.blankToNull(f.getIban()));
        b.setSwift(ItemService.blankToNull(f.getSwift()));
        b.setBranch(ItemService.blankToNull(f.getBranch()));
        b.setCurrency(f.getCurrency().trim().toUpperCase());
        b.setActive(f.isActive());
        if (f.isDefaultAccount() && f.isActive()) {
            repo.findByDefaultAccountTrue().ifPresent(o -> { if (!o.getId().equals(b.getId())) { o.setDefaultAccount(false); repo.saveAndFlush(o); } });
            b.setDefaultAccount(true);
        } else {
            b.setDefaultAccount(false);
        }
        repo.save(b);
        // account numbers/IBANs are deliberately not written to the audit log
        audit.log("BANK_SAVE", "BankAccount", b.getId(), b.getBankName());
        return b;
    }
}
