package com.wallet.api.domain.exception;

public class IdempotencyConflictException extends WalletException {

    public IdempotencyConflictException(String detail) {
        super(ErrorCode.IDEMPOTENCY_CONFLICT, detail);
    }
}
