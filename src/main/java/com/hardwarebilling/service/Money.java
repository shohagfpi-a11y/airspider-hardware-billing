package com.hardwarebilling.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Money helpers. ROUNDING POLICY (applies to UI, database, PDF, print and reports):
 * every monetary amount derived from quantity x price, discount or tax is rounded
 * HALF_UP to the currency's decimal places (AED = 2) once per line component.
 * Invoice totals are the exact sums of the rounded line amounts, so printed lines always add up.
 * Unit prices may carry up to 6 decimals; quantities up to 4.
 */
public final class Money {
    private Money() {}

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    public static int scaleFor(String currencyCode) {
        try {
            return Math.max(0, Currency.getInstance(currencyCode).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    public static BigDecimal round(BigDecimal v, int scale) {
        return v.setScale(scale, ROUNDING);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Parses an amount that must not have more decimals than the currency allows. */
    public static BigDecimal exact(BigDecimal v, int scale) {
        if (v == null) throw new BusinessException("Amount is required");
        try {
            return v.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BusinessException("Amount can have at most " + scale + " decimal places");
        }
    }
}
