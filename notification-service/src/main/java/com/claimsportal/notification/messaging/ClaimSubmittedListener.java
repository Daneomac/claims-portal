package com.claimsportal.notification.messaging;

import com.claimsportal.notification.application.ClaimConfirmationService;
import com.claimsportal.notification.application.ConfirmationEmail;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code ClaimSubmitted} events from {@code notification.claim-submitted} and hands
 * them to {@link ClaimConfirmationService}.
 *
 * <p>The container acks the message only after this method returns, which is after the
 * service's transaction has committed. If it throws, the listener's retry policy decides
 * what happens next (ADR 0004, {@link MessagingConfiguration}).
 */
@Component
class ClaimSubmittedListener {

    private final ClaimConfirmationService confirmations;
    private final Validator validator;

    ClaimSubmittedListener(ClaimConfirmationService confirmations, Validator validator) {
        this.confirmations = confirmations;
        this.validator = validator;
    }

    @RabbitListener(queues = MessagingConfiguration.CLAIM_SUBMITTED_QUEUE)
    void onClaimSubmitted(ClaimSubmittedEvent event) {
        checkProcessable(event);
        ClaimSubmittedEvent.Data data = event.data();
        confirmations.sendConfirmation(event.eventId(), ConfirmationEmail.forClaim(
                data.policyholder().email(),
                data.policyholder().firstName(),
                data.claimNumber(),
                data.policyNumber(),
                data.lossType(),
                data.lossDate()));
    }

    /**
     * Rejects events this service doesn't understand. The version is checked first because a
     * different version may have a different shape. A breaking change arrives as v2 (ADR
     * 0005); until this service supports it, v2 events wait in the dead-letter queue and
     * can be replayed after an upgrade.
     */
    private void checkProcessable(ClaimSubmittedEvent event) {
        if (!ClaimSubmittedEvent.TYPE.equals(event.eventType())
                || !Integer.valueOf(ClaimSubmittedEvent.SUPPORTED_VERSION).equals(event.eventVersion())) {
            throw new UnprocessableEventException("Unsupported event " + event.eventType() + " v"
                    + event.eventVersion() + " (event " + event.eventId() + ")");
        }

        Set<ConstraintViolation<ClaimSubmittedEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            // Field paths and rule messages only; the rejected values could be personal information
            String problems = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new UnprocessableEventException("Invalid " + ClaimSubmittedEvent.TYPE + " event "
                    + event.eventId() + ": " + problems);
        }
    }
}
