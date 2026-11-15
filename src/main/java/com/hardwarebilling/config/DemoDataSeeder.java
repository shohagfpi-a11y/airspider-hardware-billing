package com.hardwarebilling.config;

import com.hardwarebilling.domain.*;
import com.hardwarebilling.domain.Enums.ItemType;
import com.hardwarebilling.domain.Enums.PaymentMethod;
import com.hardwarebilling.domain.Enums.Role;
import com.hardwarebilling.domain.Enums.WarrantyUnit;
import com.hardwarebilling.repo.AppUserRepository;
import com.hardwarebilling.repo.CompanyProfileRepository;
import com.hardwarebilling.service.*;
import com.hardwarebilling.service.CompanyService.CompanyForm;
import com.hardwarebilling.service.CustomerService.CustomerForm;
import com.hardwarebilling.service.InvoiceRequests.InvoiceRequest;
import com.hardwarebilling.service.InvoiceRequests.LineRequest;
import com.hardwarebilling.service.ItemService.ItemForm;
import com.hardwarebilling.service.PaymentService.AllocationRequest;
import com.hardwarebilling.service.PaymentService.ReceiptRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * DEVELOPMENT ONLY sample data, active only with the "local" profile AND app.seed-demo-data=true.
 * Recreates the reference invoice (company, buyer, 21 hardware lines, delivery notes, LPO, 5% VAT) with a generated logo,
 * a generated demo stamp and signature. Demo users have well-known passwords: never run this on a real server.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final AppUserRepository users;
    private final CompanyService company;
    private final CompanyProfileRepository companyRepo;
    private final FileStorageService files;
    private final NumberingService numbering;
    private final UserService userService;
    private final BankService banks;
    private final CustomerService customers;
    private final ItemService items;
    private final InvoiceService invoices;
    private final PaymentService payments;

    public DemoDataSeeder(AppUserRepository users, CompanyService company, CompanyProfileRepository companyRepo, FileStorageService files,
                          NumberingService numbering, UserService userService, BankService banks, CustomerService customers,
                          ItemService items, InvoiceService invoices, PaymentService payments) {
        this.users = users;
        this.company = company;
        this.companyRepo = companyRepo;
        this.files = files;
        this.numbering = numbering;
        this.userService = userService;
        this.banks = banks;
        this.customers = customers;
        this.items = items;
        this.invoices = invoices;
        this.payments = payments;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (users.count() > 0) return;
        log.warn("Seeding DEMO data (development only)...");

        // ---- company (details from the reference invoice)
        CompanyForm cf = new CompanyForm();
        cf.setLegalName("AL Shahab Al Abyad Sanitary & Electricity WR Tr. LLC");
        cf.setAddress("Al Majaz 3");
        cf.setCity("Sharjah");
        cf.setCountry("UAE");
        cf.setTaxNumber("100286773500003");
        cf.setMobile("+971 58 830 6571");
        cf.setPhone("06 545 4056");
        cf.setDefaultPaymentTerms("Payment due within 30 days of the invoice date.");
        cf.setDeclarationText("We declare that this invoice shows the actual price of the goods described and that all particulars are true and correct.");
        company.create(cf);
        numbering.raiseTo("INVOICE", 12946);
        String logoFile = files.storeBytes(DemoImages.logo("AL SHAHAB AL ABYAD", "SANITARY & ELECTRICITY"));
        String stampFile = files.storeBytes(DemoImages.stamp("AL SHAHAB AL ABYAD SANITARY & ELECTRICITY", "WR. TR. LLC  -  SHARJAH",
                "AL MAJAZ 3", "TRN 100286773500003"));
        String signatureFile = files.storeBytes(DemoImages.signature());
        CompanyProfile cp = companyRepo.findById(1L).orElseThrow();
        cp.setLogoFile(logoFile);
        cp.setStampFile(stampFile);
        cp.setSignatureFile(signatureFile);
        companyRepo.save(cp);
        userService.createInitialAdmin("admin", "Demo Administrator", "Demo#Admin1234");

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "demo-seed", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            user("staff", "Demo Staff", Role.STAFF, "Demo#Staff1234");
            user("viewer", "Demo Read-only", Role.READ_ONLY, "Demo#View12345");

            BankService.BankForm b = new BankService.BankForm();
            b.setBankName("Demo National Bank");
            b.setAccountHolder("AL Shahab Al Abyad Sanitary & Electricity WR Tr. LLC");
            b.setAccountNumber("0000 1234 5678");
            b.setIban("AE00 0000 0000 1234 5678 901");
            b.setSwift("DEMOAEAD");
            b.setBranch("Sharjah");
            b.setDefaultAccount(true);
            Long bankId = banks.save(b).getId();

            // ---- customers (the buyer on the reference invoice + two more)
            Customer munir = cust("Munir R M Al Kaloti", null, null, "065566617", "100364055200003", "Al Majaz 3, Sharjah - UAE");
            Customer blueGulf = cust("Blue Gulf Builders", "Sara", "+971 55 222 2222", null, null, "Industrial Area 2, Sharjah");
            cust("Walk-in Customer", null, null, null, null, null);

            // ---- the 21 lines of the reference invoice (qty x rate, 5% VAT: 1,114.50 + 55.73 = 1,170.23)
            Object[][] ref = {
                    {"1 Gang switch", "pcs", "4", "4"}, {"13A socket", "pcs", "10", "6"}, {"Led Bulb white & yellow", "pcs", "48", "1.5"},
                    {"Hand shower with hose", "set", "1", "20"}, {"Window handle", "pcs", "1", "6"}, {"Door stopper", "pcs", "1", "3"},
                    {"Jotun paint grey 4011", "tin", "1", "100"}, {"Tissue holder", "pcs", "3", "10"}, {"Jotun paint white and off white", "tin", "1", "240"},
                    {"Berger oil paint", "tin", "4", "72"}, {"Paint Brush", "pcs", "3", "5"}, {"Soap stand steel", "pcs", "1", "4.5"},
                    {"Roller Big", "pcs", "1", "10"}, {"Silicon white & clear", "pcs", "1", "20"}, {"Hinges", "pair", "3", "13"},
                    {"Sink mixer", "pcs", "1", "60"}, {"Door cylinder", "pcs", "2", "24"}, {"Magic pipe", "pcs", "2", "5"},
                    {"Led Spot light 7W", "pcs", "5", "4"}, {"Majoon small", "pcs", "1", "5"}, {"Pop up waste", "pcs", "2", "24"}};
            List<LineRequest> lines = new ArrayList<>();
            for (Object[] r : ref) {
                Item it = item((String) r[0], (String) r[1], (String) r[3], ((String) r[0]).startsWith("Sink mixer") || ((String) r[0]).startsWith("Hand shower"),
                        ((String) r[0]).equals("Sink mixer") ? "SM-2026-0457" : null);
                boolean warranty = it.getName().equals("Sink mixer");   // one line shows a warranty on the invoice
                lines.add(new LineRequest(it.getId(), it.getCode(), it.getSerialNo(), it.getName(), new BigDecimal((String) r[2]),
                        it.getUnit(), it.getDefaultPrice(), null, it.getTaxRate(), warranty, warranty ? 1 : null, warranty ? "YEARS" : null,
                        warranty ? "Manufacturing defects only. Proof of purchase required." : null));
            }
            ItemForm svc = new ItemForm();
            svc.setItemType(ItemType.SERVICE);
            svc.setName("Installation labour");
            svc.setUnit("job");
            svc.setDefaultPrice(new BigDecimal("150"));
            svc.setTaxRate(new BigDecimal("5"));
            items.create(svc);

            LocalDate refDate = LocalDate.of(2026, 11, 5);
            Invoice reference = invoices.saveDraft(new InvoiceRequest(null, null, munir.getId(), refDate, refDate.plusDays(30), "1523, 1524",
                    "pur-ord-2026-0429", refDate, bankId, "Payment due within 30 days of the invoice date.", null, lines));
            reference = invoices.issue(reference.getId());               // -> INV-12946, total 1,170.23
            payments.createReceipt(new ReceiptRequest(munir.getId(), refDate.plusDays(2), new BigDecimal("500.00"), PaymentMethod.CASH, null, null,
                    "Part payment at counter", "seed-ref-1", List.of(new AllocationRequest(reference.getId(), new BigDecimal("500.00")))));

            // overdue invoice for the same buyer
            Item paint = items.get(items.search("Berger", 1).get(0).id());
            LocalDate old = LocalDate.now().minusDays(60);
            Invoice overdue = invoices.saveDraft(new InvoiceRequest(null, null, munir.getId(), old, old.plusDays(30), "1498", null, old, null, null, null,
                    List.of(line(paint, "5"))));
            invoices.issue(overdue.getId());

            // the AED 1,050 acceptance scenario for Blue Gulf Builders: steps 1-4 done, step 5 left for you
            LocalDate today = LocalDate.now();
            Payment adv = payments.createReceipt(new ReceiptRequest(blueGulf.getId(), today.minusDays(3), new BigDecimal("300.00"), PaymentMethod.BANK_TRANSFER,
                    bankId, "TRX-ADV-1", "Advance before invoice", "seed-2", List.of()));
            LineRequest pack = new LineRequest(null, null, null, "Site plumbing & electrical works package", BigDecimal.ONE, "job",
                    new BigDecimal("952.380952"), null, new BigDecimal("5"), false, null, null, null);
            Invoice works = invoices.saveDraft(new InvoiceRequest(null, null, blueGulf.getId(), today.minusDays(2), today.plusDays(28), null, null, null,
                    bankId, null, null, List.of(pack)));
            invoices.issue(works.getId());
            payments.allocate(adv.getId(), works.getId(), new BigDecimal("300.00"), today.minusDays(2));
            payments.createReceipt(new ReceiptRequest(blueGulf.getId(), today.minusDays(1), new BigDecimal("500.00"), PaymentMethod.CHEQUE, null,
                    "CHQ-1001", null, "seed-3", List.of(new AllocationRequest(works.getId(), new BigDecimal("500.00")))));

            // a draft to edit
            invoices.saveDraft(new InvoiceRequest(null, null, blueGulf.getId(), today, today.plusDays(30), null, null, null, null, null,
                    "Draft example", List.of(lines.get(1), lines.get(15))));
            log.warn("Demo data ready (reference invoice {}). Demo logins (development only): admin / staff / viewer, see README.", reference.getInvoiceNumber());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void user(String username, String name, Role role, String password) {
        UserService.UserForm u = new UserService.UserForm();
        u.setUsername(username);
        u.setFullName(name);
        u.setRole(role);
        u.setPassword(password);
        userService.save(u);
    }

    private Customer cust(String name, String contact, String mobile, String phone, String trn, String address) {
        CustomerForm f = new CustomerForm();
        f.setName(name);
        f.setContactPerson(contact);
        f.setMobile(mobile);
        f.setPhone(phone);
        f.setTaxNumber(trn);
        f.setBillingAddress(address);
        return customers.save(f);
    }

    private Item item(String name, String unit, String price, boolean warranty, String serialNo) {
        ItemForm f = new ItemForm();
        f.setName(name);
        f.setSerialNo(serialNo);
        f.setUnit(unit);
        f.setDefaultPrice(new BigDecimal(price));
        f.setTaxRate(new BigDecimal("5"));
        if (warranty) {
            f.setWarrantyEnabled(true);
            f.setWarrantyDuration(12);
            f.setWarrantyUnit(WarrantyUnit.MONTHS);
            f.setWarrantyTerms("Manufacturing defects only. Proof of purchase required.");
        }
        return items.create(f);
    }

    private static LineRequest line(Item i, String qty) {
        return new LineRequest(i.getId(), i.getCode(), null, i.getName(), new BigDecimal(qty), i.getUnit(), i.getDefaultPrice(), null, i.getTaxRate(),
                false, null, null, null);
    }
}
