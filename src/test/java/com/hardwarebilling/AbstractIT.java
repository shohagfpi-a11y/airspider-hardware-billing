package com.hardwarebilling;

import com.hardwarebilling.domain.*;
import com.hardwarebilling.repo.AppUserRepository;
import com.hardwarebilling.service.*;
import com.hardwarebilling.service.CompanyService.CompanyForm;
import com.hardwarebilling.service.CustomerService.CustomerForm;
import com.hardwarebilling.service.InvoiceRequests.InvoiceRequest;
import com.hardwarebilling.service.InvoiceRequests.LineRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@Import(TestDatabaseConfig.class)
@ActiveProfiles("test")
public abstract class AbstractIT {
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired protected CompanyService company;
    @Autowired protected UserService userService;
    @Autowired protected AppUserRepository userRepo;
    @Autowired protected CustomerService customers;
    @Autowired protected ItemService items;
    @Autowired protected InvoiceService invoices;
    @Autowired protected PaymentService payments;
    @Autowired protected BankService banks;
    @Autowired protected ReportService reports;
    @Autowired protected StatementService statements;
    @Autowired protected DocumentService documents;
    @Autowired protected PdfService pdf;

    @BeforeEach
    void baseSetup() {
        synchronized (AbstractIT.class) {
            // every Spring context in the test run has its own fresh database: set it up once per database
            if (!company.exists()) {
                CompanyForm cf = new CompanyForm();
                cf.setLegalName("Test Hardware LLC");
                cf.setTaxNumber("100000000000003");
                cf.setCity("Sharjah");
                cf.setCountry("UAE");
                company.create(cf);
            }
            if (userRepo.count() == 0) userService.createInitialAdmin("admin", "Admin", "Test#Password123");
        }
        asAdmin();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    protected static void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-" + role.toLowerCase(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    protected static void asAdmin() { as("ADMIN"); }

    protected static BigDecimal bd(String s) { return new BigDecimal(s); }

    protected Customer newCustomer() {
        CustomerForm f = new CustomerForm();
        f.setName("Customer " + SEQ.incrementAndGet());
        return customers.save(f);
    }

    protected static LineRequest line(String desc, String qty, String price, String tax) {
        return new LineRequest(null, null, null, desc, bd(qty), "pcs", bd(price), null, bd(tax), false, null, null, null);
    }

    protected Invoice draft(Customer c, LineRequest... lines) {
        LocalDate today = company.today();
        return invoices.saveDraft(new InvoiceRequest(null, null, c.getId(), today, today.plusDays(30), null, null, null, null, null, null, List.of(lines)));
    }

    /** Issues an invoice whose total is exactly {@code total} (no tax). */
    protected Invoice issued(Customer c, String total) {
        Invoice d = draft(c, line("Test goods", "1", total, "0"));
        return invoices.issue(d.getId());
    }

    protected PaymentService.ReceiptRequest receipt(Customer c, String amount, PaymentService.AllocationRequest... allocs) {
        return new PaymentService.ReceiptRequest(c.getId(), company.today(), bd(amount), Enums.PaymentMethod.CASH, null, null, null,
                java.util.UUID.randomUUID().toString(), List.of(allocs));
    }

    protected PaymentService.AllocationRequest alloc(Invoice i, String amount) {
        return new PaymentService.AllocationRequest(i.getId(), bd(amount));
    }
}
