package com.hardwarebilling.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** "UAE Dirhams One Thousand One Hundred Seventy and Twenty-Three Fils Only" - used on invoices and receipts. */
public final class AmountInWords {
    private AmountInWords() {}

    private static final String[] ONES = {"Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};
    private static final String[] SCALES = {"", " Thousand", " Million", " Billion", " Trillion"};

    /** major unit (plural), minor unit name. */
    private static final Map<String, String[]> NAMES = Map.ofEntries(
            Map.entry("AED", new String[]{"UAE Dirhams", "Fils"}),
            Map.entry("SAR", new String[]{"Saudi Riyals", "Halalas"}),
            Map.entry("QAR", new String[]{"Qatari Riyals", "Dirhams"}),
            Map.entry("OMR", new String[]{"Omani Rials", "Baisa"}),
            Map.entry("KWD", new String[]{"Kuwaiti Dinars", "Fils"}),
            Map.entry("BHD", new String[]{"Bahraini Dinars", "Fils"}),
            Map.entry("USD", new String[]{"US Dollars", "Cents"}),
            Map.entry("EUR", new String[]{"Euros", "Cents"}),
            Map.entry("GBP", new String[]{"Pounds Sterling", "Pence"}),
            Map.entry("INR", new String[]{"Indian Rupees", "Paise"}),
            Map.entry("BDT", new String[]{"Bangladeshi Taka", "Paisa"}),
            Map.entry("PKR", new String[]{"Pakistani Rupees", "Paisa"}));

    public static String of(BigDecimal amount, String currency) {
        if (amount == null) return "";
        int scale = Money.scaleFor(currency);
        BigDecimal a = amount.abs().setScale(scale, RoundingMode.HALF_UP);
        long major = a.longValue();
        long minor = scale == 0 ? 0 : a.subtract(BigDecimal.valueOf(major)).movePointRight(scale).longValue();
        String[] n = NAMES.getOrDefault(currency, new String[]{currency, "cents"});
        StringBuilder sb = new StringBuilder();
        if (amount.signum() < 0) sb.append("Minus ");
        sb.append(n[0]).append(' ').append(words(major));
        if (minor > 0) sb.append(" and ").append(words(minor)).append(' ').append(n[1]);
        return sb.append(" Only").toString();
    }

    static String words(long n) {
        if (n == 0) return ONES[0];
        StringBuilder out = new StringBuilder();
        int scale = 0;
        while (n > 0) {
            int chunk = (int) (n % 1000);
            if (chunk > 0) {
                String part = below1000(chunk) + SCALES[scale];
                out.insert(0, out.length() == 0 ? part : part + " ");
            }
            n /= 1000;
            scale++;
        }
        return out.toString();
    }

    private static String below1000(int n) {
        StringBuilder sb = new StringBuilder();
        if (n >= 100) {
            sb.append(ONES[n / 100]).append(" Hundred");
            n %= 100;
            if (n > 0) sb.append(' ');
        }
        if (n >= 20) {
            sb.append(TENS[n / 10]);
            if (n % 10 > 0) sb.append('-').append(ONES[n % 10]);
        } else if (n > 0) {
            sb.append(ONES[n]);
        }
        return sb.toString();
    }
}
