package com.hardwarebilling;

import com.hardwarebilling.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class WebSecurityIT extends AbstractIT {
    @Autowired MockMvc mvc;

    @Test
    void anonymousUsersAreSentToLogin_andLoginPageIsPublic() throws Exception {
        mvc.perform(get("/invoices")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/api/items/search").param("q", "a")).andExpect(status().is3xxRedirection());
    }

    @Test
    void postsWithoutCsrfTokenAreRejected() throws Exception {
        mvc.perform(post("/api/items").with(user("a").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void readOnlyCanViewButNotChange() throws Exception {
        mvc.perform(get("/invoices").with(user("v").roles("READ_ONLY"))).andExpect(status().isOk());
        mvc.perform(get("/reports/invoices").with(user("v").roles("READ_ONLY"))).andExpect(status().isOk());
        mvc.perform(post("/payments").with(user("v").roles("READ_ONLY")).with(csrf())
                .param("customerId", "1").param("paymentDate", "2026-01-01").param("amount", "1").param("method", "CASH")).andExpect(status().isForbidden());
        mvc.perform(post("/api/invoices").with(user("v").roles("READ_ONLY")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void staffCannotReachAdminAreasOrDestructiveActions() throws Exception {
        Customer c = newCustomer();
        Invoice inv = issued(c, "10");
        for (String url : new String[]{"/settings", "/users", "/banks", "/audit"}) {
            mvc.perform(get(url).with(user("s").roles("STAFF"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/settings").with(user("s").roles("STAFF")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/users").with(user("s").roles("STAFF")).with(csrf())).andExpect(status().isForbidden());
        // void / reverse / refund are ADMIN-only inside the service layer too
        mvc.perform(post("/invoices/" + inv.getId() + "/void").with(user("s").roles("STAFF")).with(csrf()).param("reason", "x")).andExpect(status().isForbidden());
    }

    @Test
    void adminCanOpenAdminScreens() throws Exception {
        for (String url : new String[]{"/settings", "/users", "/banks", "/audit", "/", "/customers", "/items", "/payments"}) {
            mvc.perform(get(url).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        }
    }

    @Test
    void createItemFromInvoiceGrid_thenFindItByShortCodeAndPartialName() throws Exception {
        String body = "{\"itemType\":\"PRODUCT\",\"name\":\"Brass padlock 40mm\",\"unit\":\"pcs\",\"defaultPrice\":\"15.5\",\"taxRate\":\"5\",\"active\":true}";
        String created = mvc.perform(post("/api/items").with(user("s").roles("STAFF")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code", startsWith("P"))).andReturn().getResponse().getContentAsString();
        String code = created.replaceAll(".*\"code\":\"(P\\d+)\".*", "$1");
        mvc.perform(get("/api/items/search").param("q", code).with(user("s").roles("STAFF")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Brass padlock 40mm")));
        mvc.perform(get("/api/items/search").param("q", "padlock").with(user("s").roles("STAFF")))
                .andExpect(status().isOk()).andExpect(content().string(containsString(code)));
        mvc.perform(get("/api/items/similar").param("name", "brass pad").with(user("s").roles("STAFF")))
                .andExpect(status().isOk()).andExpect(content().string(containsString(code)));
    }

    @Test
    void invoiceCalcEndpointUsesServerRounding_andPdfDownloadWorks() throws Exception {
        Customer c = newCustomer();
        Invoice inv = issued(c, "42");
        String lines = "[{\"quantity\":\"3\",\"unitPrice\":\"0.335\",\"taxRate\":\"5\"}]";
        mvc.perform(post("/api/invoices/calc").with(user("s").roles("STAFF")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(lines))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grand").value(1.06));
        mvc.perform(get("/invoices/" + inv.getId() + "/pdf").with(user("v").roles("READ_ONLY")))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/invoices/lookup").param("number", inv.getInvoiceNumber()).with(user("v").roles("READ_ONLY")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/invoices/" + inv.getId()));
    }

    @Test
    void csvExportEscapesFormulaInjection() throws Exception {
        var f = new com.hardwarebilling.service.CustomerService.CustomerForm();
        f.setName("=HYPERLINK(\"http://evil\")");
        Customer c = customers.save(f);
        issued(c, "10");   // gives the customer a balance so it appears in the credits report
        String csv = mvc.perform(get("/reports/credits.csv").with(user("a").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(csv).contains("'=HYPERLINK").doesNotContain(",=HYPERLINK");
    }

    @Test
    void everyMainScreenRendersWithRealData() throws Exception {
        Customer c = newCustomer();
        Invoice inv = issued(c, "500");
        Payment p = payments.createReceipt(receipt(c, "700", alloc(inv, "500")));
        Invoice draft = draft(c, line("d", "1", "5", "5"));
        String[] urls = {"/", "/invoices", "/invoices/" + inv.getId(), "/invoices/" + draft.getId(), "/invoices/" + draft.getId() + "/edit", "/invoices/new",
                "/invoices/" + inv.getId() + "/print", "/payments", "/payments/" + p.getId(), "/payments/new?customerId=" + c.getId() + "&invoiceId=" + inv.getId(),
                "/payments/" + p.getId() + "/receipt", "/customers", "/customers/" + c.getId(), "/customers/" + c.getId() + "/statement",
                "/customers/" + c.getId() + "/statement.pdf", "/customers/" + c.getId() + "/statement.csv", "/payments/" + p.getId() + "/receipt.pdf",
                "/items", "/items/new", "/reports", "/reports/invoices", "/reports/outstanding", "/reports/receipts", "/reports/credits",
                "/reports/invoices.csv", "/reports/outstanding.csv", "/reports/receipts.csv", "/account/password",
                "/reports/invoices/print", "/reports/outstanding/print", "/reports/receipts/print", "/reports/credits/print",
                "/reports/invoices.pdf", "/reports/outstanding.pdf", "/reports/receipts.pdf", "/reports/credits.pdf",
                "/reports/invoices/print?customerId=" + c.getId() + "&status=ISSUED&autoprint=1"};
        for (String url : urls) {
            mvc.perform(get(url).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        }
    }

    @Test
    void companyLogoIsPublicForTheSignInPageButOtherFilesAreNot() throws Exception {
        mvc.perform(get("/brand/logo")).andExpect(status().isNotFound());          // public endpoint (no logo uploaded in tests)
        mvc.perform(get("/files/00000000-0000-0000-0000-000000000000.png")).andExpect(status().is3xxRedirection());   // still needs login
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Welcome back")));
    }

    @Test
    void setupPageIsClosedOnceAdministratorExists() throws Exception {
        mvc.perform(get("/setup")).andExpect(status().is3xxRedirection());
    }
}
