package com.wallet.api.domain;

import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.InvalidRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money rules of the domain. BRL has 2 decimals: an amount is valid only when it is strictly
 * positive, has at most 2 decimal places and fits {@code NUMBER(19,2)}.
 */
public final class Amounts {

    public static final int SCALE = 2;
    public static final int MAX_INTEGER_DIGITS = 17;

    private Amounts() {
    }

    public static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidRequestException(ErrorCode.INVALID_AMOUNT, "valor é obrigatório");
        }
        if (amount.signum() <= 0) {
            throw new InvalidRequestException(ErrorCode.INVALID_AMOUNT, "valor deve ser maior que zero");
        }
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new InvalidRequestException(ErrorCode.INVALID_AMOUNT,
                    "valor deve ter no máximo 2 casas decimais");
        }
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidRequestException(ErrorCode.INVALID_AMOUNT,
                    "valor excede o limite suportado de " + MAX_INTEGER_DIGITS + " dígitos inteiros");
        }
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Puts a value read from the database on the API's money scale.
     *
     * <p>Oracle's {@code NUMBER} keeps the scale of the stored arithmetic result: {@code 0},
     * {@code 100.5} and {@code 150.75} can all come back from a {@code NUMBER(19,2)} column. The
     * HTTP contract is always 2 decimals, so every amount crossing the boundary is scaled here
     * instead of relying on the driver (or on how the value happened to be written).
     */
    public static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
