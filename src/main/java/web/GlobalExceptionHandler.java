package web;

import web.ApiError.FieldIssue;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> issues.add(new FieldIssue(fe.getField(), fe.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> issues.add(new FieldIssue(ge.getObjectName(), ge.getDefaultMessage())));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, issues, null);
    }

    /** @RequestParam / @PathVariable constraint violations (Spring 6.1+ built-in method validation). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        ex.getParameterValidationResults().forEach(r -> {
            String name = r.getMethodParameter().getParameterName();
            r.getResolvableErrors().forEach(e -> issues.add(new FieldIssue(name, e.getDefaultMessage())));
        });
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, issues, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldIssue> issues = ex.getConstraintViolations().stream()
                .map(v -> new FieldIssue(v.getPropertyPath().toString(), v.getMessage())).toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, issues, null);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing, malformed, or contains an invalid value", request, null, null);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing", request, null, null);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        Class<?> required = ex.getRequiredType();
        String hint = required != null && required.isEnum()
                ? "must be one of " + Arrays.toString(required.getEnumConstants())
                : "has an invalid value";
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Parameter '" + name + "' " + hint, request,
                List.of(new FieldIssue(name, hint)), null);
    }

    // ---- domain + security

    @ExceptionHandler(ClaimNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(ClaimNotFoundException ex, WebRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, null, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action",
                request, null, null);
    }

    // ---- everything else the framework raises (405, 415, ResponseStatusException, ...) + last-resort 500

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        String message = ex instanceof ErrorResponse er && er.getBody().getDetail() != null
                ? er.getBody().getDetail() : reason(statusCode);
        return build(statusCode, reason(statusCode).toUpperCase().replace(' ', '_'), message, request, null, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled exception, errorId={}", errorId, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong. Quote the errorId when contacting support.", request, null, errorId);
    }

    // ---- helpers

    private ResponseEntity<Object> build(HttpStatusCode status, String code, String message, WebRequest request,
                                         List<FieldIssue> issues, String errorId) {
        String path = request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
        ApiError body = new ApiError(Instant.now(), status.value(), reason(status), code, message, path,
                issues == null || issues.isEmpty() ? null : issues, errorId);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static String reason(HttpStatusCode status) {
        return status instanceof HttpStatus hs ? hs.getReasonPhrase() : String.valueOf(status.value());
    }
}
