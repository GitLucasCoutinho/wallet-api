package com.wallet.api.web;

import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.WalletException;
import com.wallet.api.infrastructure.OracleErrorTranslator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Translates every exception into the same error envelope, with a semantic HTTP status:
 *
 * <ul>
 *   <li>business rule violated -> the status declared on {@link ErrorCode}
 *       (404 unknown wallet, 422 insufficient funds, 409 idempotency conflict, 400 invalid input);</li>
 *   <li>malformed request / failed validation -> 400 with field level details;</li>
 *   <li>database unavailable or concurrency conflict -> 503 (the client may retry);</li>
 *   <li>anything else -> 500 without leaking internals (the {@code traceId} links to the log).</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String WRITE_ERROR_LOG = "request out of the happy path code={} detail={} path={}";

    private final ApiErrorFactory errorFactory;
    private final OracleErrorTranslator errorTranslator;

    public GlobalExceptionHandler(ApiErrorFactory errorFactory, OracleErrorTranslator errorTranslator) {
        this.errorFactory = errorFactory;
        this.errorTranslator = errorTranslator;
    }

    @ExceptionHandler(WalletException.class)
    public ResponseEntity<ApiErrorResponse> handleWalletException(WalletException exception,
                                                                  HttpServletRequest request) {
        logRejection(exception.code(), exception.detail(), request, exception);
        return respond(exception.code(), exception.detail(), request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception,
                                                             HttpServletRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, "um ou mais campos são inválidos", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception,
                                                                      HttpServletRequest request) {
        List<FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(lastNode(violation), violation.getMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, "um ou mais campos são inválidos", request, violations);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException exception,
                                                                HttpServletRequest request) {
        boolean idempotencyKey = "Idempotency-Key".equalsIgnoreCase(exception.getHeaderName());
        ErrorCode code = idempotencyKey ? ErrorCode.MISSING_IDEMPOTENCY_KEY : ErrorCode.VALIDATION_ERROR;
        return respond(code, "cabeçalho " + exception.getHeaderName() + " é obrigatório", request, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException exception,
                                                                   HttpServletRequest request) {
        return respond(ErrorCode.INVALID_PARAMETER,
                "parâmetro '" + exception.getParameterName() + "' é obrigatório", request, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                               HttpServletRequest request) {
        return respond(ErrorCode.INVALID_PARAMETER,
                "parâmetro '" + exception.getName() + "' possui valor inválido", request, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception,
                                                                 HttpServletRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST, "corpo da requisição inválido ou malformado", request, null);
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception,
                                                                     HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED,
                "método " + exception.getMethod() + " não é suportado neste recurso", request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException exception,
                                                            HttpServletRequest request) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "tipo de conteúdo não suportado; use application/json", request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception,
                                                                HttpServletRequest request) {
        return respond(ErrorCode.NOT_ACCEPTABLE,
                "nenhuma representação disponível para o Accept enviado", request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException exception,
                                                             HttpServletRequest request) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, "recurso não encontrado", request, null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDataAccess(DataAccessException exception,
                                                             HttpServletRequest request) {
        WalletException translated = errorTranslator.translate(exception);
        logRejection(translated.code(), translated.detail(), request, translated);
        return respond(translated.code(), translated.detail(), request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error(WRITE_ERROR_LOG, ErrorCode.INTERNAL_ERROR, exception.getMessage(),
                request.getRequestURI(), exception);
        return respond(ErrorCode.INTERNAL_ERROR, "erro interno inesperado", request, null);
    }

    private ResponseEntity<ApiErrorResponse> respond(ErrorCode code, String message,
                                                     HttpServletRequest request,
                                                     List<FieldViolation> violations) {
        ApiErrorResponse body = errorFactory.create(code, message, request.getRequestURI(), violations);
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    /**
     * Severity follows what an operator needs to see: 5xx is an incident and carries the stack;
     * a business rejection (409/422) is a warning with the path, because it changes the client's
     * behaviour; a plain 400 is client noise and stays at debug.
     */
    private void logRejection(ErrorCode code, String detail, HttpServletRequest request, Exception exception) {
        if (code.status().is5xxServerError() || code.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            log.error(WRITE_ERROR_LOG, code, detail, request.getRequestURI(), exception);
            return;
        }
        if (code.status() == HttpStatus.CONFLICT || code.status() == HttpStatus.UNPROCESSABLE_ENTITY) {
            log.info(WRITE_ERROR_LOG, code, detail, request.getRequestURI());
            return;
        }
        log.debug(WRITE_ERROR_LOG, code, detail, request.getRequestURI());
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 ? path.substring(lastDot + 1) : path;
    }
}
