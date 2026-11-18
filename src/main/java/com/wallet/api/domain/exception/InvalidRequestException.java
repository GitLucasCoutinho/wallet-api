package com.wallet.api.domain.exception;

/**
 * Input that is syntactically valid JSON but breaks a domain rule (negative amount, blank
 * holder, malformed idempotency key, out of range pagination, ...).
 */
public class InvalidRequestException extends WalletException {

    public InvalidRequestException(ErrorCode code, String detail) {
        super(code, detail);
    }
}
