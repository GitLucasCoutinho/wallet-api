package com.wallet.api.domain.exception;

import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Catalog of API errors. The name of each constant is the machine readable {@code code} returned
 * in the response body; the PL/SQL package raises the same names inside {@code ORA-20xxx}
 * messages, which is what makes the mapping from database error to HTTP status explicit and
 * testable.
 */
public enum ErrorCode {

    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Carteira não encontrada"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Saldo insuficiente"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Conflito de chave de idempotência"),

    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "Valor inválido"),
    INVALID_TRANSACTION_TYPE(HttpStatus.BAD_REQUEST, "Tipo de operação inválido"),
    INVALID_HOLDER(HttpStatus.BAD_REQUEST, "Dados do titular inválidos"),
    INVALID_DESCRIPTION(HttpStatus.BAD_REQUEST, "Descrição inválida"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Chave de idempotência inválida"),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Chave de idempotência ausente"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Parâmetro inválido"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Requisição inválida"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido"),

    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Autenticação obrigatória"),
    INVALID_API_KEY(HttpStatus.UNAUTHORIZED, "Chave de API inválida"),

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Recurso não encontrado"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de mídia não suportado"),

    CONCURRENT_MODIFICATION(HttpStatus.SERVICE_UNAVAILABLE, "Conflito de concorrência, tente novamente"),
    DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Banco de dados indisponível"),

    LEDGER_IMMUTABLE(HttpStatus.INTERNAL_SERVER_ERROR, "Lançamento imutável"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /**
     * Stable, documented error type (RFC 9457 {@code type}) for this code.
     */
    public String type() {
        return "https://wallet-api/errors/" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
