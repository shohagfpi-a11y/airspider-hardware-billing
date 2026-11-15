package com.hardwarebilling.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Pure, stateless invoice maths (tax-exclusive pricing). See {@link Money} for the rounding policy. */
public final class InvoiceCalculator {
    private InvoiceCalculator() {}

    public record LineInput(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent, BigDecimal taxRate) {}

    public record LineResult(BigDecimal gross, BigDecimal discount, BigDecimal net, BigDecimal tax, BigDecimal total) {}

    public record Totals(List<LineResult> lines, BigDecimal gross, BigDecimal discount,
                         BigDecimal net, BigDecimal tax, BigDecimal grand) {}

    public static LineResult line(LineInput in, int scale) {
        BigDecimal qty = Money.nz(in.quantity());
        BigDecimal price = Money.nz(in.unitPrice());
        BigDecimal discPct = Money.nz(in.discountPercent());
        BigDecimal taxRate = Money.nz(in.taxRate());

        BigDecimal gross = Money.round(qty.multiply(price), scale);
        BigDecimal discount = Money.round(gross.multiply(discPct).divide(Money.HUNDRED), scale);
        BigDecimal net = gross.subtract(discount);
        BigDecimal tax = Money.round(net.multiply(taxRate).divide(Money.HUNDRED), scale);
        return new LineResult(gross, discount, net, tax, net.add(tax));
    }

    public static Totals calculate(List<LineInput> inputs, int scale) {
        List<LineResult> results = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO.setScale(scale), discount = gross, net = gross, tax = gross, grand = gross;
        for (LineInput in : inputs) {
            LineResult r = line(in, scale);
            results.add(r);
            gross = gross.add(r.gross());
            discount = discount.add(r.discount());
            net = net.add(r.net());
            tax = tax.add(r.tax());
            grand = grand.add(r.total());
        }
        return new Totals(results, gross, discount, net, tax, grand);
    }
}
