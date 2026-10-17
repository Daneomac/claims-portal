package com.claimsportal.api.infrastructure;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Carries the trace across the outbox's asynchronous gap (ADR 0002, PLAN section 9).
 *
 * <p>Within an HTTP request, Spring's observation filter makes the request's span "current",
 * so the trace ID appears in the logs, and the RabbitMQ template would add a
 * {@code traceparent} header to anything it sent. But the outbox relay sends the event later,
 * on a scheduler thread where there's no current span. So:
 * <ol>
 *   <li>when the event is saved, {@link #currentTraceParent()} writes the request's trace
 *       context into the row's {@code trace_parent} column as a W3C {@code traceparent}
 *       ({@code 00-<trace ID>-<span ID>-<flags>});</li>
 *   <li>when the relay publishes the row, {@link #startPublishSpan} starts a span whose parent
 *       is that saved context, and the relay makes it current while it sends. The RabbitMQ
 *       template's own send span is then a child of it, and its {@code traceparent} header
 *       carries the request's trace ID to the consumer.</li>
 * </ol>
 * Both directions go through Micrometer's {@link Propagator}, the same W3C code that reads
 * and writes HTTP and AMQP headers, rather than formatting the header by hand.
 */
@Component
class OutboxTracing {

    /** The W3C Trace Context header name, used as the key in the one-entry "carrier" map. */
    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    OutboxTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * The current trace context as a W3C {@code traceparent}, or null if nothing is being
     * traced (for example, code run outside a request).
     */
    String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /**
     * Starts the span the relay publishes one event in. With a saved {@code traceParent} the
     * span joins the trace of the request that saved the event; without one (a row written
     * before tracing existed, or outside a request) it starts a new trace, and the event is
     * still published. The caller must make the span current ({@link #withSpan}) and end it.
     */
    Span startPublishSpan(String traceParent, UUID eventId) {
        Span.Builder builder = traceParent != null
                ? propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get) // parent = the saved context
                : tracer.spanBuilder().setNoParent();                           // a new trace
        return builder.name("outbox publish")
                .tag("outbox.event.id", eventId.toString()) // an ID only: no personal information in spans
                .start();
    }

    /**
     * Makes {@code span} the current span until the returned scope is closed: log lines get
     * its trace ID, and spans started meanwhile (the RabbitMQ send) become its children.
     */
    Tracer.SpanInScope withSpan(Span span) {
        return tracer.withSpan(span);
    }
}
