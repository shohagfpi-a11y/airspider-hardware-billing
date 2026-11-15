package com.hardwarebilling;

import com.hardwarebilling.domain.*;
import com.hardwarebilling.domain.Enums.DocStatus;
import com.hardwarebilling.domain.Enums.WarrantyUnit;
import com.hardwarebilling.service.BusinessException;
import com.hardwarebilling.service.BankService.BankForm;
import com.hardwarebilling.service.CompanyService.CompanyForm;
import com.hardwarebilling.service.CustomerService.CustomerForm;
import com.hardwarebilling.service.InvoiceRequests.InvoiceRequest;
import com.hardwarebilling.service.InvoiceRequests.LineRequest;
import com.hardwarebilling.service.ItemService.ItemDto;
import com.hardwarebilling.service.ItemService.ItemForm;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceIntegrityIT extends AbstractIT {

    private ItemForm itemForm(String name, String price) {
        ItemForm f = new ItemForm();
        f.setName(name);
        f.setUnit("pcs");
        f.setDefaultPrice(bd(price));
        f.setTaxRate(bd("5"));
        return f;
    }

    private LineRequest lineFor(Item i, String qty) {
        return new LineRequest(i.getId(), i.getCode(), null, i.getName(), bd(qty), i.getUnit(), i.getDefaultPrice(), null, i.getTaxRate(),
                i.isWarrantyEnabled(), i.getWarrantyDuration(), i.getWarrantyUnit() == null ? null : i.getWarrantyUnit().name(), i.getWarrantyTerms());
    }

    @Test
    void newItemCreatedFromAnInvoiceIsSearchableByCodeAndPartialNameOnTheNextInvoice() {
        // create the item (what the inline "Create new item" button does) and use it on invoice A
        Item created = items.create(itemForm("Stainless Steel Hinge 4in", "12.50"));
        assertThat(created.getCode()).matches("P\\d{5}");
        Customer c = newCustomer();
        Invoice a = draft(c, lineFor(created, "4"));
        assertThat(a.getLines().get(0).getItemCode()).isEqualTo(created.getCode());

        // another invoice later: exact short code, lower-case code, and partial name all find it
        assertThat(items.search(created.getCode(), 10)).extracting(ItemDto::code).contains(created.getCode());
        assertThat(items.search(created.getCode().toLowerCase(), 10)).extracting(ItemDto::code).first().isEqualTo(created.getCode());
        assertThat(items.search("steel hin", 10)).extracting(ItemDto::code).contains(created.getCode());
        Invoice b = draft(c, lineFor(created, "2"));
        assertThat(b.getLines().get(0).getUnitPrice()).isEqualByComparingTo("12.50");
    }

    @Test
    void typingANumberFindsThatProductNumber_andAScannedBarcodeFindsTheBarcodeItem() {
        ItemForm f = itemForm("Barcode test widget", "9");
        f.setBarcode("6291041500213");
        Item i = items.create(f);
        long n = Long.parseLong(i.getCode().substring(1));
        // "12" finds P00012 first (a number is treated as the product number), scanner value finds the barcode item first
        assertThat(items.search(String.valueOf(n), 10)).first().extracting(ItemDto::code).isEqualTo(i.getCode());
        assertThat(items.search("6291041500213", 10)).first().extracting(ItemDto::code).isEqualTo(i.getCode());
        assertThat(items.search("widget", 10)).extracting(ItemDto::code).contains(i.getCode());   // a word lists related products
        ItemForm dup = itemForm("Another widget", "9");
        dup.setBarcode("6291041500213");
        assertThatThrownBy(() -> items.create(dup)).isInstanceOf(BusinessException.class).hasMessageContaining("already used");
    }

    @Test
    void bankDetailsArePrintedOnlyWhenABankWasChosen() {
        BankForm bf = new BankForm();
        bf.setBankName("Chosen Bank");
        bf.setAccountHolder("Test Hardware LLC");
        Long bankId = banks.save(bf).getId();
        Customer c = newCustomer();
        LocalDate d = LocalDate.now();
        Invoice cash = invoices.saveDraft(new InvoiceRequest(null, null, c.getId(), d, d.plusDays(5), null, null, null, null, null, null, List.of(line("x", "1", "5", "0"))));
        Invoice bank = invoices.saveDraft(new InvoiceRequest(null, null, c.getId(), d, d.plusDays(5), null, null, null, bankId, null, null, List.of(line("x", "1", "5", "0"))));
        assertThat(documents.invoice(cash.getId()).bank()).isNull();
        assertThat(pdf.html("invoice-document", java.util.Map.of("d", documents.invoice(cash.getId())), false)).doesNotContain("Pay by bank transfer");
        assertThat(documents.invoice(bank.getId()).bank().bankName()).isEqualTo("Chosen Bank");
        assertThat(pdf.html("invoice-document", java.util.Map.of("d", documents.invoice(bank.getId())), false)).contains("Pay by bank transfer").contains("Chosen Bank");
    }

    @Test
    void productSerialNumberIsOfferedToTheInvoiceAndPrinted() {
        ItemForm f = itemForm("Water pump 1HP", "450");
        f.setSerialNo("WP-77821");
        Item pump = items.create(f);
        assertThat(items.search(pump.getCode(), 5)).first().extracting(ItemDto::serialNo).isEqualTo("WP-77821");
        Customer c = newCustomer();
        Invoice inv = draft(c, new LineRequest(pump.getId(), pump.getCode(), pump.getSerialNo(), pump.getName(), bd("1"), "pcs", bd("450"), null, bd("5"), false, null, null, null));
        assertThat(documents.invoice(inv.getId()).lines().get(0).serial()).isEqualTo("WP-77821");
    }

    @Test
    void duplicateNamesAreReportedButDistinctVariantsAreAllowed() {
        items.create(itemForm("Door Handle Chrome", "30"));
        assertThat(items.similar("door handle")).isNotEmpty();
        Item variant = items.create(itemForm("Door Handle Chrome Large", "45"));
        assertThat(variant.getCode()).isNotBlank();
    }

    @Test
    void deactivatedItemCodeIsNeverReused() {
        Item i = items.create(itemForm("Temp item", "1"));
        ItemForm f = itemForm("Temp item", "1");
        f.setId(i.getId());
        f.setActive(false);
        items.update(f);
        assertThat(items.search(i.getCode(), 5)).isEmpty();             // hidden from invoice search
        Item next = items.create(itemForm("Another", "1"));
        assertThat(next.getCode()).isNotEqualTo(i.getCode());
        assertThat(Long.parseLong(next.getCode().substring(1))).isGreaterThan(Long.parseLong(i.getCode().substring(1)));
    }

    @Test
    void invoiceChangesDoNotSilentlyUpdateTheCatalog() {
        Item i = items.create(itemForm("Cable 2.5mm", "100"));
        Customer c = newCustomer();
        LineRequest changed = new LineRequest(i.getId(), i.getCode(), null, i.getName(), bd("1"), "roll", bd("90"), null, bd("5"), false, null, null, null);
        invoices.saveDraft(new InvoiceRequest(null, null, c.getId(), LocalDate.now(), LocalDate.now().plusDays(5), null, null, null, null, null, null, List.of(changed)));
        assertThat(items.get(i.getId()).getDefaultPrice()).isEqualByComparingTo("100");
        items.updateCatalogPrice(i.getId(), bd("90"));                 // only the explicit action changes it
        assertThat(items.get(i.getId()).getDefaultPrice()).isEqualByComparingTo("90");
    }

    @Test
    void warrantyIsCalculatedFromDeliveryDateAndSnapshotted() {
        ItemForm f = itemForm("Water heater 50L", "800");
        f.setWarrantyEnabled(true);
        f.setWarrantyDuration(2);
        f.setWarrantyUnit(WarrantyUnit.YEARS);
        f.setWarrantyTerms("Parts and labour");
        Item heater = items.create(f);
        Customer c = newCustomer();
        LocalDate invoiceDate = LocalDate.of(2026, 3, 10), delivery = LocalDate.of(2026, 3, 20);
        Invoice inv = invoices.saveDraft(new InvoiceRequest(null, null, c.getId(), invoiceDate, invoiceDate.plusDays(30), null, null, delivery, null, null, null, List.of(lineFor(heater, "1"))));
        InvoiceLine l = invoices.get(inv.getId()).getLines().get(0);
        assertThat(l.getWarrantyStart()).isEqualTo(delivery);                       // delivery date wins when present
        assertThat(l.getWarrantyEnd()).isEqualTo(LocalDate.of(2028, 3, 20));

        Invoice noDelivery = invoices.saveDraft(new InvoiceRequest(null, null, c.getId(), invoiceDate, invoiceDate.plusDays(30), null, null, null, null, null, null, List.of(lineFor(heater, "1"))));
        assertThat(invoices.get(noDelivery.getId()).getLines().get(0).getWarrantyStart()).isEqualTo(invoiceDate);

        // items without warranty carry no warranty data (no misleading text)
        Item plain = items.create(itemForm("Plain screw", "1"));
        Invoice p = draft(c, lineFor(plain, "10"));
        assertThat(invoices.get(p.getId()).getLines().get(0).isWarrantyEnabled()).isFalse();
        assertThat(documents.invoice(p.getId()).hasWarranty()).isFalse();

        // editing the product afterwards never changes an issued invoice's warranty
        Invoice issued = invoices.issue(inv.getId());
        ItemForm edit = itemForm("Water heater 50L", "800");
        edit.setId(heater.getId());
        edit.setWarrantyEnabled(true);
        edit.setWarrantyDuration(10);
        edit.setWarrantyUnit(WarrantyUnit.DAYS);
        items.update(edit);
        InvoiceLine after = invoices.get(issued.getId()).getLines().get(0);
        assertThat(after.getWarrantyDuration()).isEqualTo(2);
        assertThat(after.getWarrantyUnit()).isEqualTo("YEARS");
        assertThat(after.getWarrantyEnd()).isEqualTo(LocalDate.of(2028, 3, 20));
    }

    @Test
    void issuedInvoiceKeepsCompanyCustomerBankAndLinePrices_afterMasterDataChanges() {
        BankForm bf = new BankForm();
        bf.setBankName("Original Bank");
        bf.setAccountHolder("Test Hardware LLC");
        bf.setIban("AE111111111111111111111");
        Long bankId = banks.save(bf).getId();

        CustomerForm cf = new CustomerForm();
        cf.setName("Original Buyer LLC");
        cf.setBillingAddress("Old Street 1");
        cf.setTaxNumber("TRN-OLD");
        Customer c = customers.save(cf);

        Item item = items.create(itemForm("Snapshot item", "100"));
        LocalDate d = LocalDate.now();
        Invoice draft = invoices.saveDraft(new InvoiceRequest(null, null, c.getId(), d, d.plusDays(30), null, "LPO-9", null, bankId, null, null, List.of(lineFor(item, "2"))));
        Invoice issued = invoices.issue(draft.getId());
        var before = documents.invoice(issued.getId());
        assertThat(before.customer().name()).isEqualTo("Original Buyer LLC");

        // change everything that is master data
        cf.setId(c.getId());
        cf.setName("Renamed Buyer");
        cf.setBillingAddress("New Street 99");
        cf.setTaxNumber("TRN-NEW");
        customers.save(cf);
        bf.setId(bankId);
        bf.setBankName("Different Bank");
        bf.setIban("AE999999999999999999999");
        banks.save(bf);
        ItemForm ef = itemForm("Renamed item", "555");
        ef.setId(item.getId());
        items.update(ef);
        CompanyForm co = company.toForm(company.get());
        String oldName = co.getLegalName();
        co.setLegalName("Totally New Company Name");
        co.setTaxNumber("999");
        company.update(co, null, null, null);

        var after = documents.invoice(issued.getId());
        assertThat(after.customer().name()).isEqualTo("Original Buyer LLC");
        assertThat(after.customer().address()).isEqualTo("Old Street 1");
        assertThat(after.customer().taxNumber()).isEqualTo("TRN-OLD");
        assertThat(after.bank().bankName()).isEqualTo("Original Bank");
        assertThat(after.bank().iban()).isEqualTo("AE111111111111111111111");
        assertThat(after.company().legalName()).isEqualTo(oldName);
        assertThat(after.lines().get(0).description()).isEqualTo("Snapshot item");
        assertThat(after.lines().get(0).price()).isEqualTo("100.00");
        assertThat(after.grand()).isEqualTo(before.grand());

        // restore for other tests
        co.setLegalName(oldName);
        co.setTaxNumber("100000000000003");
        company.update(co, null, null, null);
    }

    @Test
    void issuedInvoicesCannotBeEditedOrDeleted() {
        Customer c = newCustomer();
        Invoice inv = issued(c, "10");
        assertThatThrownBy(() -> invoices.saveDraft(new InvoiceRequest(inv.getId(), null, c.getId(), LocalDate.now(), LocalDate.now(), null, null, null, null, null, null, List.of(line("x", "1", "1", "0")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Only draft");
        assertThatThrownBy(() -> invoices.deleteDraft(inv.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> invoices.issue(inv.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> invoices.voidInvoice(inv.getId(), "")).isInstanceOf(BusinessException.class).hasMessageContaining("reason");
    }

    @Test
    void invoiceNumbersAreNeverReusedAfterVoidAndCannotBeLowered() {
        Customer c = newCustomer();
        Invoice one = issued(c, "10");
        invoices.voidInvoice(one.getId(), "test");
        Invoice two = issued(c, "10");
        long n1 = Long.parseLong(one.getInvoiceNumber().replaceAll("\\D", ""));
        long n2 = Long.parseLong(two.getInvoiceNumber().replaceAll("\\D", ""));
        assertThat(n2).isEqualTo(n1 + 1);
        CompanyForm f = company.toForm(company.get());
        f.setNextInvoiceNumber(1L);
        assertThatThrownBy(() -> company.update(f, null, null, null)).isInstanceOf(BusinessException.class).hasMessageContaining("never reused");
    }

    @Test
    void serverRecalculatesTotalsAndRejectsBadInput() {
        Customer c = newCustomer();
        Invoice inv = draft(c, line("A", "3", "10.10", "5"), line("B", "1.5", "2", "0"));
        // 30.30 + 5% (1.515 -> 1.52) = 31.82 ; 3.00 ; total 34.82
        assertThat(inv.getGrandTotal()).isEqualByComparingTo("34.82");
        assertThat(inv.getTaxTotal()).isEqualByComparingTo("1.52");
        assertThatThrownBy(() -> draft(c, line("bad", "0", "1", "0"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> draft(c, line("bad", "1", "-1", "0"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> draft(c, line("", "1", "1", "0"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> invoices.saveDraft(new InvoiceRequest(null, null, c.getId(), LocalDate.now(), LocalDate.now().minusDays(1), null, null, null, null, null, null, List.of(line("x", "1", "1", "0")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Due date");
        assertThat(invoices.get(inv.getId()).getDocStatus()).isEqualTo(DocStatus.DRAFT);
    }

}
