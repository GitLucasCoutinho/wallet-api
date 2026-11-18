package com.wallet.api.web;

import com.wallet.api.domain.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Protects every endpoint with the {@code X-API-Key} header.
 *
 * <p>Only the operational endpoints (health/info) and the API documentation are public: they
 * expose no wallet data and let the container health check run without a secret.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-API-Key";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/actuator/health",
            "/actuator/info",
            "/v3/api-docs",
            "/swagger-ui",
            "/swagger-ui.html"
    );

    private final ApiKeyVerifier verifier;
    private final ErrorResponseWriter errorWriter;

    public ApiKeyAuthenticationFilter(ApiKeyVerifier verifier, ErrorResponseWriter errorWriter) {
        this.verifier = verifier;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER_NAME);
        if (apiKey == null || apiKey.isBlank()) {
            errorWriter.write(request, response, ErrorCode.AUTHENTICATION_REQUIRED,
                    "cabeçalho " + HEADER_NAME + " é obrigatório");
            return;
        }
        if (!verifier.isValid(apiKey)) {
            errorWriter.write(request, response, ErrorCode.INVALID_API_KEY,
                    "chave de API inválida");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
