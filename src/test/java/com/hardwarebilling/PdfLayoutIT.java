package com.hardwarebilling;

import com.hardwarebilling.domain.*;
import com.hardwarebilling.domain.Enums.WarrantyUnit;
import com.hardwarebilling.service.InvoiceCalculator;
import com.hardwarebilling.service.InvoiceCalculator.LineInput;
import com.hardwarebilling.service.InvoiceRequests.LineRequest;
import com.hardwarebilling.service.ItemService.ItemForm;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Renders real PDFs (short and multi-page) and checks page count, content and that totals match the server's numbers. */
class PdfLayoutIT extends AbstractIT {
    private static final Path OUT = Path.of("target", "pdf-samples");

    private record Pdf(int pages, String text) {}

    private Pdf render(String template, Map<String, Object> model, String name) throws Exception {
        byte[] bytes = pdf.pdf(template, model);
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name), bytes);
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            // page previews for visual inspection (target/pdf-samples/<name>-pN.png)
            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(doc);
            for (int i = 0; i < Math.min(doc.getNumberOfPages(), 3); i++) {
                javax.imageio.ImageIO.write(renderer.renderImageWithDPI(i, 80), "png", OUT.resolve(name.replace(".pdf", "") + "-p" + (i + 1) + ".png").toFile());
            }
            return new Pdf(doc.getNumberOfPages(), new PDFTextStripper().getText(doc));
        }
    }

    @Test
    void shortInvoice_isOnePage_showsWarrantyBarcodeNumberAndMatchingTotals() throws Exception {
        ItemForm f = new ItemForm();
        f.setName("Jotun paint white 4L");
        f.setDefaultPrice(bd("78"));
        f.setTaxRate(bd("5"));
        f.setWarrantyEnabled(true);
        f.setWarrantyDuration(12);
        f.setWarrantyUnit(WarrantyUnit.MONTHS);
        f.setWarrantyTerms("Manufacturer defects only");
        Item item = items.create(f);
        Customer c = newCustomer();
        Invoice inv = invoices.issue(draft(c,
                new LineRequest(item.getId(), item.getCode(), "SN-1234", item.getName(), bd("5"), "tin", bd("78"), null, bd("5"), true, 12, "MONTHS", "Manufacturer defects only"),
                line("Plain screws box", "3", "4.50", "5")).getId());

        var doc = documents.invoice(inv.getId());
        Pdf p = render("invoice-document", Map.of("d", doc), "short-invoice.pdf");
        assertThat(p.pages()).isEqualTo(1);
        assertThat(p.text()).contains(inv.getInvoiceNumber()).contains("TAX INVOICE").contains("Warranty: 12 months")
                .contains("Manufacturer defects only").contains("S/N: SN-1234");
        // amount in words and the 3-day notice are printed
        assertThat(p.text().replaceAll("\s+", " ")).contains("AMOUNT IN WORDS").contains("UAE Dirhams")
                .contains("inform us within 3 days");
        assertThat(p.text()).doesNotContain("DRAFT");

        // totals in the PDF are the server's: recompute independently with the calculator
        var t = InvoiceCalculator.calculate(List.of(new LineInput(bd("5"), bd("78"), null, bd("5")), new LineInput(bd("3"), bd("4.50"), null, bd("5"))), 2);
        assertThat(inv.getGrandTotal()).isEqualByComparingTo(t.grand());
        assertThat(p.text()).contains(doc.grand()).contains(doc.tax()).contains(doc.net());
        assertThat(doc.grand()).isEqualTo(String.format("%,.2f", t.grand()));

        // the browser print view uses the same model: same strings
        String html = pdf.html("invoice-document", Map.of("d", doc), true);
        assertThat(html).contains(doc.grand()).contains(inv.getInvoiceNumber()).contains("window.print()");
        String pdfHtml = pdf.html("invoice-document", Map.of("d", doc), false);
        assertThat(pdfHtml).doesNotContain("window.print()");   // no buttons in output
    }

    @Test
    void longInvoice_flowsOverSeveralPages_withRepeatedHeaderAndNoLostLines() throws Exception {
        Customer c = newCustomer();
        List<LineRequest> lines = new ArrayList<>();
        for (int i = 1; i <= 90; i++) {
            String name = "Line item number " + i + " with a deliberately very long product description that has to wrap onto several lines "
                    + "without being clipped or overlapping the neighbouring columns of the table - SUPERLONGWORDWITHOUTSPACES" + "X".repeat(40);
            lines.add(line(name, String.valueOf(i % 7 + 1), "12.34", "5"));
        }
        Invoice draft = invoices.saveDraft(new com.hardwarebilling.service.InvoiceRequests.InvoiceRequest(null, null, c.getId(), company.today(), company.today().plusDays(30),
                null, null, null, null, "Pay within 30 days.", "Thank you for your business.", lines));
        Invoice inv = invoices.issue(draft.getId());
        Pdf p = render("invoice-document", Map.of("d", documents.invoice(inv.getId())), "long-invoice.pdf");
        assertThat(p.pages()).isGreaterThan(2);
        assertThat(p.text()).contains("Line item number 1 ").contains("Line item number 90 ").contains("Page 1 of " + p.pages());
        long headerRepeats = p.text().lines().filter(l -> l.toLowerCase().contains("unit price")).count();
        assertThat(headerRepeats).isGreaterThanOrEqualTo(p.pages() - 1);   // header row printed on (nearly) every page
        assertThat(p.text()).contains(documents.invoice(inv.getId()).grand());
    }

    @Test
    void a4ReportHasCompanyHeaderThenTitle_andFlowsOverPages() throws Exception {
        // render through the real template with a minimal model (same keys the controller provides)
        var table = new com.hardwarebilling.web.ReportController.Table("invoices", "Invoice list", "note",
                java.util.List.of("Invoice", "Customer", "Total"), java.util.List.of(2),
                java.util.stream.IntStream.range(0, 120).mapToObj(i -> java.util.List.of(
                        new com.hardwarebilling.web.ReportController.Cell("INV-" + i, null),
                        new com.hardwarebilling.web.ReportController.Cell("Customer " + i, null),
                        new com.hardwarebilling.web.ReportController.Cell(i + ".00", null))).toList(),
                java.util.List.of("Total", "", "7,140.00"),
                java.util.List.of(new com.hardwarebilling.web.ReportController.Stat("Invoices", "120")));
        Map<String, Object> model = new java.util.HashMap<>();
        model.put("t", table);
        model.put("co", com.hardwarebilling.service.Snapshots.CompanySnap.of(company.get()));
        model.put("logo", null);
        model.put("filters", java.util.List.of("Invoice date: all dates", "Customer: All customers"));
        model.put("generated", "15/11/2026 10:00");
        model.put("user", "admin");
        model.put("currency", "AED");
        model.put("landscape", false);
        model.put("qs", "");
        model.put("footer", "Test Hardware LLC | Invoice list");
        Pdf p = render("report-document", model, "report-invoices.pdf");
        assertThat(p.pages()).isGreaterThan(1);
        String text = p.text().replaceAll("\s+", " ");
        assertThat(text.indexOf("Test Hardware LLC")).isLessThan(text.indexOf("INVOICE LIST"));   // company first, then the report title
        assertThat(text).contains("Sharjah").contains("INV-0 ").contains("INV-119").contains("7,140.00").contains("Page 2 of");
    }

    @Test
    void draftIsClearlyMarked_andNoBarcodeUntilIssued() throws Exception {
        Customer c = newCustomer();
        Invoice d = draft(c, line("Draft item", "1", "10", "0"));
        var doc = documents.invoice(d.getId());
        assertThat(doc.draft()).isTrue();
        assertThat(doc.barcode()).isNull();
        Pdf p = render("invoice-document", Map.of("d", doc), "draft-invoice.pdf");
        assertThat(p.text()).contains("DRAFT");
    }

    @Test
    void receiptAndStatementPdfsRender() throws Exception {
        Customer c = newCustomer();
        Invoice inv = issued(c, "500");
        Payment p = payments.createReceipt(receipt(c, "700", alloc(inv, "500")));
        Pdf r = render("receipt-document", Map.of("d", documents.receipt(p.getId())), "receipt.pdf");
        assertThat(r.pages()).isEqualTo(1);
        assertThat(r.text()).contains(p.getReceiptNo()).contains("700.00").contains("200.00");
        var st = documents.statement(c, company.today().minusDays(30), company.today());
        Pdf s = render("statement-document", Map.of("d", st, "customerId", c.getId(), "fromIso", "2026-01-01", "toIso", "2026-12-31"), "statement.pdf");
        assertThat(s.text().replaceAll("\s+", " ")).contains("STATEMENT OF ACCOUNT").contains(inv.getInvoiceNumber()).contains("-200.00");
    }

    @Test
    void paidInvoicePdfShowsPaymentsAsOfDateAndAdvance() throws Exception {
        Customer c = newCustomer();
        Payment adv = payments.createReceipt(new com.hardwarebilling.service.PaymentService.ReceiptRequest(c.getId(), company.today().minusDays(5), bd("300"),
                Enums.PaymentMethod.CASH, null, null, null, java.util.UUID.randomUUID().toString(), List.of()));
        Invoice inv = issued(c, "1000");
        payments.allocate(adv.getId(), inv.getId(), bd("300"), null);
        var doc = documents.invoice(inv.getId());
        assertThat(doc.hasAdvance()).isTrue();
        Pdf p = render("invoice-document", Map.of("d", doc), "invoice-with-advance.pdf");
        assertThat(p.text()).contains("Advance applied").contains("Balance due").contains("700.00").contains("as of");
    }
}
