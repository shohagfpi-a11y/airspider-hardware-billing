package com.hardwarebilling.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Minimal RFC-4180 CSV writer with spreadsheet formula-injection protection. */
public final class Csv {
    private Csv() {}

    public static byte[] write(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("﻿");   // BOM so Excel reads UTF-8
        line(sb, headers);
        for (List<String> r : rows) line(sb, r);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    static String cell(String v) {
        if (v == null) return "";
        // a leading = + - @ would be executed as a formula by spreadsheet software (keep plain negative numbers)
        if (!v.isEmpty() && "=+@".indexOf(v.charAt(0)) >= 0 || v.startsWith("-") && !v.matches("-[0-9.,]+")) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
