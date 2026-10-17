package com.claimsportal.api.web;

import com.claimsportal.api.domain.InvalidClaimException;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Turns errors into RFC 9457 {@link ProblemDetail} responses. Field problems are listed in an
 * {@code errors} array of {@code {field, message}} so the web form can show each one next to
 * its input. Responses never contain stack traces, exception messages or class names.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives standard responses for Spring MVC's
 * own exceptions (405, 415 and so on); the two overrides below customize the 400s.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final String INVALID_CLAIM = "Some of the claim details aren't valid. Correct them and try again.";
    static final String UNREADABLE_BODY = "The request body isn't valid JSON, or a value has the wrong type.";
    static final String UNEXPECTED_ERROR = "Something went wrong on our side. Please try again later.";

    private final Tracer tracer;

    ApiExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    /** Bean Validation failures on the request body. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail(INVALID_CLAIM);
        problem.setProperty("errors", oneErrorPerField(ex.getBindingResult().getFieldErrors()));
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Malformed JSON, or a value Jackson can't convert (e.g. an unknown loss type). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, UNREADABLE_BODY);
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            // Only the field path is shared, never Jackson's message (it names Java types)
            problem.setProperty("errors", List.of(new ApiFieldError(jsonPath(mismatch), "This value isn't valid")));
        }
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** A business rule from the domain, such as the loss date window. */
    @ExceptionHandler
    ProblemDetail handleInvalidClaim(InvalidClaimException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, INVALID_CLAIM);
        problem.setProperty("errors", List.of(new ApiFieldError(ex.field(), ex.getMessage())));
        return problem;
    }

    /**
     * Anything else is our fault: log it in full, tell the client nothing about it except the
     * trace ID. The error log line carries the same trace ID, so a customer or support person
     * quoting it leads straight to the stack trace.
     */
    @ExceptionHandler
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled error while processing a request", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR);
        String traceId = TraceIdResponseFilter.currentTraceId(tracer);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    /**
     * A field can fail several constraints at once (an empty name is both blank and doesn't
     * match the pattern). Report one message per field, preferring "required" because it's
     * the most useful thing to tell the customer.
     */
    private static List<ApiFieldError> oneErrorPerField(List<FieldError> fieldErrors) {
        Map<String, FieldError> byField = new TreeMap<>();
        for (FieldError error : fieldErrors) {
            byField.merge(error.getField(), error, (kept, other) -> isRequiredError(other) ? other : kept);
        }
        return byField.values().stream()
                .map(error -> new ApiFieldError(error.getField(), error.getDefaultMessage()))
                .toList();
    }

    private static boolean isRequiredError(FieldError error) {
        return "NotNull".equals(error.getCode()) || "NotBlank".equals(error.getCode());
    }

    /** E.g. {@code loss.type}, matching the paths used for validation errors. */
    private static String jsonPath(MismatchedInputException ex) {
        return ex.getPath().stream()
                .map(reference -> reference.getPropertyName())
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
    }
}
