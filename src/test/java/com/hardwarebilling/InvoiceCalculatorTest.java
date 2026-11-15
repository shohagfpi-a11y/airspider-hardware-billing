package com.hardwarebilling;

import com.hardwarebilling.service.BusinessException;
import com.hardwarebilling.service.InvoiceCalculator;
import com.hardwarebilling.service.InvoiceCalculator.LineInput;
import com.hardwarebilling.service.Money;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceCalculatorTest {
    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void sampleInvoiceFromReferencePdf() {
        // 22 lines of qty x rate from the sample, 5% VAT. Total 1114.50, VAT 55.725 -> 55.73 (half-up), grand 1170.23
        String[][] rows = {{"4","4"},{"10","6"},{"48","1.5"},{"1","20"},{"1","6"},{"1","3"},{"1","100"},{"3","10"},{"1","240"},{"4","72"},
                {"3","5"},{"1","4.5"},{"1","10"},{"1","20"},{"3","13"},{"1","60"},{"2","24"},{"2","5"},{"5","4"},{"1","5"},{"2","24"}};
        // one combined line at 5% mirrors the sample's invoice-level VAT exactly
        BigDecimal sum = BigDecimal.ZERO;
        for (String[] r : rows) sum = sum.add(bd(r[0]).multiply(bd(r[1])));
        var t = InvoiceCalculator.calculate(List.of(new LineInput(BigDecimal.ONE, sum, null, bd("5"))), 2);
        assertThat(t.net()).isEqualByComparingTo("1114.50");
        assertThat(t.tax()).isEqualByComparingTo("55.73");
        assertThat(t.grand()).isEqualByComparingTo("1170.23");
    }

    @Test
    void roundsHalfUpPerLineAndTotalsAreSumOfRoundedLines() {
        // 3 x 0.335 = 1.005 -> 1.01 (half-up); tax 5% of 1.01 = 0.0505 -> 0.05
        var t = InvoiceCalculator.calculate(List.of(
                new LineInput(bd("3"), bd("0.335"), null, bd("5")),
                new LineInput(bd("3"), bd("0.335"), null, bd("5"))), 2);
        assertThat(t.lines().get(0).gross()).isEqualByComparingTo("1.01");
        assertThat(t.lines().get(0).tax()).isEqualByComparingTo("0.05");
        assertThat(t.gross()).isEqualByComparingTo("2.02");
        assertThat(t.tax()).isEqualByComparingTo("0.10");
        assertThat(t.grand()).isEqualByComparingTo("2.12");
        assertThat(t.lines().stream().map(InvoiceCalculator.LineResult::total).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(t.grand());
    }

    @Test
    void supportsDecimalQuantityHighPrecisionPriceAndDiscount() {
        // 2.5 x 12.345678 = 30.864195 -> 30.86; 10% discount = 3.086 -> 3.09; net 27.77; 5% tax 1.3885 -> 1.39
        var r = InvoiceCalculator.line(new LineInput(bd("2.5"), bd("12.345678"), bd("10"), bd("5")), 2);
        assertThat(r.gross()).isEqualByComparingTo("30.86");
        assertThat(r.discount()).isEqualByComparingTo("3.09");
        assertThat(r.net()).isEqualByComparingTo("27.77");
        assertThat(r.tax()).isEqualByComparingTo("1.39");
        assertThat(r.total()).isEqualByComparingTo("29.16");
    }

    @Test
    void neverUsesFloatingPointDrift() {
        // classic double trap: 0.1 + 0.2. With BigDecimal the sum is exactly 0.30
        var t = InvoiceCalculator.calculate(List.of(
                new LineInput(BigDecimal.ONE, bd("0.1"), null, null), new LineInput(BigDecimal.ONE, bd("0.2"), null, null)), 2);
        assertThat(t.grand()).isEqualTo(new BigDecimal("0.30"));
    }

    @Test
    void respectsCurrencyDecimalPlaces() {
        assertThat(Money.scaleFor("AED")).isEqualTo(2);
        assertThat(Money.scaleFor("BHD")).isEqualTo(3);
        assertThat(Money.scaleFor("JPY")).isEqualTo(0);
        var t = InvoiceCalculator.calculate(List.of(new LineInput(BigDecimal.ONE, bd("10.0005"), null, null)), 3);
        assertThat(t.grand()).isEqualByComparingTo("10.001");
    }

    @Test
    void amountsWithTooManyDecimalsAreRejected() {
        assertThat(Money.exact(bd("10.50"), 2)).isEqualByComparingTo("10.5");
        assertThatThrownBy(() -> Money.exact(bd("10.505"), 2)).isInstanceOf(BusinessException.class);
    }
}
