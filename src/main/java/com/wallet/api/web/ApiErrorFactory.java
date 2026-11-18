package com.wallet.api.web;

import com.wallet.api.domain.exception.ErrorCode;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Builds the error envelope so that the exception handler, the security filter and any other
 * component answer with exactly the same shape.
 */
@Component
public class ApiErrorFactory {

    public ApiErrorResponse create(ErrorCode code, String message, String path) {
        return create(code, message, path, null);
    }

    public ApiErrorResponse create(ErrorCode code, String message, String path,
                                   List<FieldViolation> violations) {
        String resolvedMessage = message == null || message.isBlank() ? code.title() : message;
        return new ApiErrorResponse(
                URI.create(code.type()),
                code.title(),
                code.status().value(),
                code.name(),
                resolvedMessage,
                path,
                MDC.get(RequestIdFilter.MDC_KEY),
                OffsetDateTime.now(ZoneOffset.UTC),
                FieldViolation.of(violations));
    }
}
