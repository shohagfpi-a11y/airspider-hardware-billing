package com.hardwarebilling.service;

import com.hardwarebilling.domain.Customer;
import com.hardwarebilling.repo.CustomerRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class CustomerService {

    @Data
    public static class CustomerForm {
        private Long id;
        @NotBlank @Size(max = 200) private String name;
        @Size(max = 120) private String contactPerson;
        @Size(max = 50) private String mobile;
        @Size(max = 50) private String phone;
        @Email @Size(max = 150) private String email;
        @Size(max = 400) private String billingAddress;
        @Size(max = 50) private String taxNumber;
        private Integer defaultPaymentTermsDays;
        @Size(max = 1000) private String notes;
        private boolean active = true;
    }

    private final CustomerRepository repo;
    private final NumberingService numbering;
    private final AuditService audit;

    public CustomerService(CustomerRepository repo, NumberingService numbering, AuditService audit) {
        this.repo = repo;
        this.numbering = numbering;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Customer> search(String q) {
        String s = q == null ? "" : q.trim().replace("%", "").replace("_", "");
        return repo.search(s, PageRequest.of(0, 300));
    }

    @Transactional(readOnly = true)
    public Customer get(Long id) {
        return repo.findById(id).orElseThrow(() -> new BusinessException("Customer not found"));
    }

    @Transactional(readOnly = true)
    public List<Customer> active() {
        return repo.findByActiveTrueOrderByName();
    }

    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public Customer save(CustomerForm f) {
        Customer c;
        boolean isNew = f.getId() == null;
        if (isNew) {
            c = new Customer();
            c.setCode("C" + String.format("%05d", numbering.next("CUSTOMER")));
        } else {
            c = get(f.getId());
        }
        c.setName(f.getName().trim());
        c.setContactPerson(ItemService.blankToNull(f.getContactPerson()));
        c.setMobile(ItemService.blankToNull(f.getMobile()));
        c.setPhone(ItemService.blankToNull(f.getPhone()));
        c.setEmail(ItemService.blankToNull(f.getEmail()));
        c.setBillingAddress(ItemService.blankToNull(f.getBillingAddress()));
        c.setTaxNumber(ItemService.blankToNull(f.getTaxNumber()));
        c.setDefaultPaymentTermsDays(f.getDefaultPaymentTermsDays());
        c.setNotes(ItemService.blankToNull(f.getNotes()));
        c.setActive(isNew || f.isActive());
        repo.save(c);
        audit.log(isNew ? "CUSTOMER_CREATE" : "CUSTOMER_UPDATE", "Customer", c.getId(), c.getCode() + " " + c.getName());
        return c;
    }
}
