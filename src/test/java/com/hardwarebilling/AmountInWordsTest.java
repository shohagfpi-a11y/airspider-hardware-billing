package com.hardwarebilling;

import com.hardwarebilling.service.AmountInWords;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class AmountInWordsTest {
    @Test
    void writesDirhamsAndFils() {
        assertThat(AmountInWords.of(new BigDecimal("1170.23"), "AED"))
                .isEqualTo("UAE Dirhams One Thousand One Hundred Seventy and Twenty-Three Fils Only");
        assertThat(AmountInWords.of(new BigDecimal("1000"), "AED")).isEqualTo("UAE Dirhams One Thousand Only");
        assertThat(AmountInWords.of(new BigDecimal("0.50"), "AED")).isEqualTo("UAE Dirhams Zero and Fifty Fils Only");
    }

    @Test
    void handlesLargeNumbersAndOtherCurrencies() {
        assertThat(AmountInWords.of(new BigDecimal("2305019.07"), "AED"))
                .isEqualTo("UAE Dirhams Two Million Three Hundred Five Thousand Nineteen and Seven Fils Only");
        assertThat(AmountInWords.of(new BigDecimal("12.345"), "BHD")).isEqualTo("Bahraini Dinars Twelve and Three Hundred Forty-Five Fils Only");
        assertThat(AmountInWords.of(new BigDecimal("99.99"), "USD")).isEqualTo("US Dollars Ninety-Nine and Ninety-Nine Cents Only");
    }
}
