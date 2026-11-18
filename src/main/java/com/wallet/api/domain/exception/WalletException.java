package com.wallet.api.domain.exception;

/**
 * Base class for every business failure that must be translated into a client visible error.
 * Carrying the {@link ErrorCode} (instead of a raw HTTP status) keeps the domain free of web
 * concerns: the exception handler decides the status, the payload and the logging level.
 */
public class WalletException extends RuntimeException {

    private final ErrorCode code;
    private final String detail;

    public WalletException(ErrorCode code, String detail) {
        this(code, detail, null);
    }

    public WalletException(ErrorCode code, String detail, Throwable cause) {
        super(detail == null ? code.title() : detail, cause);
        this.code = code;
        this.detail = detail == null ? code.title() : detail;
    }

    public ErrorCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
