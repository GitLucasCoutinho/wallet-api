package com.wallet.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.api.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes an error envelope directly to the servlet response. Used by the filters, which run
 * before Spring MVC's exception handling exists.
 */
@Component
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;
    private final ApiErrorFactory errorFactory;

    public ErrorResponseWriter(ObjectMapper objectMapper, ApiErrorFactory errorFactory) {
        this.objectMapper = objectMapper;
        this.errorFactory = errorFactory;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                errorFactory.create(code, message, request.getRequestURI()));
    }
}
