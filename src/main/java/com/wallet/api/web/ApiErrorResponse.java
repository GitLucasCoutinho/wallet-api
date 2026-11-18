package com.wallet.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Single error envelope for every failure, served as {@code application/problem+json}
 * (RFC 9457). It always carries a stable {@code code} (same value the database uses), a human
 * readable {@code message}, the request path and a {@code traceId} that correlates the response
 * with the server log.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        URI type,
        String title,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        OffsetDateTime timestamp,
        List<FieldViolation> violations
) {
}
