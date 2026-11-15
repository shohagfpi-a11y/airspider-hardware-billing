package com.hardwarebilling.repo;

import com.hardwarebilling.domain.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {
    List<BankAccount> findAllByOrderByBankNameAsc();
    List<BankAccount> findByActiveTrueOrderByBankNameAsc();
    Optional<BankAccount> findByDefaultAccountTrue();
}
