package com.hardwarebilling.web;

import com.hardwarebilling.service.CompanyService;
import com.hardwarebilling.service.Fmt;
import com.hardwarebilling.service.Money;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Template helper: ${@fmt.money(x)} uses the company currency's decimal places. */
@Component("fmt")
public class FmtBean {
    private final CompanyService company;
    private volatile int scale = 2;
    private volatile String currency = "AED";
    private volatile long loadedAt = 0;

    public FmtBean(CompanyService company) {
        this.company = company;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - loadedAt > 15_000) {
            try {
                currency = company.get().getCurrency();
                scale = Money.scaleFor(currency);
            } catch (Exception ignored) {
                // before setup
            }
            loadedAt = now;
        }
    }

    public String money(BigDecimal v) {
        refresh();
        return v == null ? "" : Fmt.money(v, scale);
    }

    public String currency() {
        refresh();
        return currency;
    }

    public String date(LocalDate d) {
        return Fmt.date(d);
    }

    public String qty(BigDecimal v) {
        return Fmt.qty(v);
    }

    public String rate(BigDecimal v) {
        return Fmt.rate(v);
    }
}
