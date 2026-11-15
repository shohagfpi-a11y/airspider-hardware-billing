package com.hardwarebilling.web;

import com.hardwarebilling.service.Fmt;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityHelpersTest {

    @Test
    void cssStringCannotBreakOutOfTheStyleBlock() {
        String evil = "Shop\"; } </style><script>alert(1)</script> {";
        String safe = Fmt.cssString(evil);
        assertThat(safe).doesNotContain("<", ">", "\"", "}", "{", ";");
        assertThat(Fmt.cssString("AL Shahab & Co | Tel 06 545 4056")).isEqualTo("AL Shahab & Co | Tel 06 545 4056");
        assertThat(Fmt.cssString(null)).isEmpty();
    }

    @Test
    void goBackLinkOnlyFollowsSameSiteReferers() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("billing.example.com");
        req.addHeader("Referer", "https://billing.example.com/invoices/7?x=1");
        assertThat(WebAdvice.sameSitePath(req)).isEqualTo("/invoices/7?x=1");

        MockHttpServletRequest other = new MockHttpServletRequest();
        other.setServerName("billing.example.com");
        other.addHeader("Referer", "https://evil.example.net/login");
        assertThat(WebAdvice.sameSitePath(other)).isNull();

        MockHttpServletRequest none = new MockHttpServletRequest();
        assertThat(WebAdvice.sameSitePath(none)).isNull();
    }
}
