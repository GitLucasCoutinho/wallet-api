package com.wallet.api.domain.exception;

public class InsufficientFundsException extends WalletException {

    public InsufficientFundsException(String detail) {
        super(ErrorCode.INSUFFICIENT_FUNDS, detail);
    }
}
