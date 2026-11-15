package com.hardwarebilling.service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** One formatting code path for HTML pages, print view and PDF so they always show identical numbers. */
public final class Fmt {
    private Fmt() {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static String money(BigDecimal v, int scale) {
        if (v == null) return "";
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        f.setMinimumFractionDigits(scale);
        f.setMaximumFractionDigits(scale);
        f.setRoundingMode(Money.ROUNDING);
        return f.format(v);
    }

    /** Unit price: at least the currency decimals, up to 6 when the stored price needs them. */
    public static String price(BigDecimal v, int scale) {
        if (v == null) return "";
        int s = Math.max(scale, Math.min(6, Math.max(0, v.stripTrailingZeros().scale())));
        return money(v, s);
    }

    public static String qty(BigDecimal v) {
        if (v == null) return "";
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    public static String rate(BigDecimal v) {
        return v == null ? "" : qty(v) + "%";
    }

    /**
     * Text that goes inside a CSS string in a <style> block (page footers). Removes everything that could end the string,
     * the declaration or the <style> element: quotes, backslashes, line breaks, angle brackets, braces, semicolons.
     */
    public static String cssString(String s) {
        if (s == null) return "";
        return s.replaceAll("[\"'\\\\\r\n<>{};]", " ").replaceAll(" {2,}", " ").trim();
    }

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }
}
