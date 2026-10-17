package com.claimsportal.api.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds an {@code X-Trace-Id} header to every response, so a caller (or a support person
 * looking at the browser's network tab) can find the request's log lines and trace. The trace
 * ID is a random identifier with no customer information in it.
 *
 * <p>Runs after Spring Boot's {@code ServerHttpObservationFilter} (which has a high
 * precedence), so the request's span has already been started and made current.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class TraceIdResponseFilter extends OncePerRequestFilter {

    static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final Tracer tracer;

    TraceIdResponseFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = currentTraceId(tracer);
        if (traceId != null) {
            // Set before the chain runs: once the body is written, headers can't be added
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
        chain.doFilter(request, response);
    }

    /** The current trace's ID, or null if there's no current span. */
    static String currentTraceId(Tracer tracer) {
        Span span = tracer.currentSpan();
        return span != null ? span.context().traceId() : null;
    }
}
