package com.wallet.api.domain.exception;

public class WalletNotFoundException extends WalletException {

    public WalletNotFoundException(long walletId) {
        super(ErrorCode.WALLET_NOT_FOUND, "Carteira " + walletId + " não encontrada");
    }

    public WalletNotFoundException(String detail) {
        super(ErrorCode.WALLET_NOT_FOUND, detail);
    }
}
