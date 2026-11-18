package com.wallet.api.domain;

import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountsTest {

    @Test
    void normalizesToTwoDecimals() {
        assertThat(Amounts.normalize(new BigDecimal("10"))).isEqualByComparingTo("10.00");
        assertThat(Amounts.normalize(new BigDecimal("10.5")).scale()).isEqualTo(2);
        assertThat(Amounts.normalize(new BigDecimal("0.01"))).isEqualByComparingTo("0.01");
    }

    @Test
    void acceptsTheLargestValueThatFitsNumber19_2() {
        BigDecimal max = new BigDecimal("99999999999999999.99");
        assertThat(Amounts.normalize(max)).isEqualByComparingTo(max);
    }

    @Test
    void rejectsValuesAboveTheColumnPrecision() {
        assertThatThrownBy(() -> Amounts.normalize(new BigDecimal("100000000000000000.00")))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((InvalidRequestException) ex).code())
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
    void rejectsNonPositiveAmounts(String amount) {
        assertThatThrownBy(() -> Amounts.normalize(new BigDecimal(amount)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((InvalidRequestException) ex).code())
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Amounts.normalize(null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("obrigatório");
    }

    @Test
    void rejectsMoreThanTwoDecimalPlaces() {
        assertThatThrownBy(() -> Amounts.normalize(new BigDecimal("10.005")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("2 casas decimais");
    }

    @Test
    void trailingZerosDoNotCountAsDecimalPlaces() {
        assertThat(Amounts.normalize(new BigDecimal("10.000"))).isEqualByComparingTo("10.00");
    }

    @Test
    void scalesValuesReadFromTheDatabaseToTwoDecimals() {
        assertThat(Amounts.scale2(new BigDecimal("0")))
                .isEqualByComparingTo("0.00")
                .hasScaleOf(Amounts.SCALE);
        assertThat(Amounts.scale2(new BigDecimal("100.5")))
                .isEqualByComparingTo("100.50")
                .hasScaleOf(Amounts.SCALE);
        assertThat(Amounts.scale2(new BigDecimal("150.75"))).hasScaleOf(Amounts.SCALE);
        assertThat(Amounts.scale2(null)).isNull();
    }
}
