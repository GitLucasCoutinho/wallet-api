package com.wallet.api.domain;

import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeysTest {

    @Test
    void acceptsAnAllowedKeyAndTrimsIt() {
        assertThat(IdempotencyKeys.requireValid("  order-1234.abc  ")).isEqualTo("order-1234.abc");
    }

    @Test
    void rejectsNullAndBlank() {
        assertThatThrownBy(() -> IdempotencyKeys.requireValid(null))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((InvalidRequestException) ex).code())
                .isEqualTo(ErrorCode.MISSING_IDEMPOTENCY_KEY);

        assertThatThrownBy(() -> IdempotencyKeys.requireValid("   "))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((InvalidRequestException) ex).code())
                .isEqualTo(ErrorCode.MISSING_IDEMPOTENCY_KEY);
    }

    @Test
    void rejectsTooShortKeys() {
        assertThatThrownBy(() -> IdempotencyKeys.requireValid("abc"))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((InvalidRequestException) ex).code())
                .isEqualTo(ErrorCode.INVALID_IDEMPOTENCY_KEY);
    }

    @Test
    void rejectsKeysLongerThanTheColumn() {
        String longKey = "k".repeat(IdempotencyKeys.MAX_LENGTH + 1);
        assertThatThrownBy(() -> IdempotencyKeys.requireValid(longKey))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((InvalidRequestException) ex).code())
                .isEqualTo(ErrorCode.INVALID_IDEMPOTENCY_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"key with space", "key/with/slash", "key+plus", "chave-com-acentuação"})
    void rejectsDisallowedCharacters(String key) {
        assertThatThrownBy(() -> IdempotencyKeys.requireValid(key))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((InvalidRequestException) ex).code())
                .isEqualTo(ErrorCode.INVALID_IDEMPOTENCY_KEY);
    }

    @Test
    void acceptsKeysAtTheBoundaries() {
        assertThat(IdempotencyKeys.requireValid("12345678")).hasSize(IdempotencyKeys.MIN_LENGTH);
        assertThat(IdempotencyKeys.requireValid("k".repeat(IdempotencyKeys.MAX_LENGTH)))
                .hasSize(IdempotencyKeys.MAX_LENGTH);
    }
}
