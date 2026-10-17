package com.claimsportal.notification.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.claimsportal.notification.application.ClaimConfirmationService;
import com.claimsportal.notification.application.ConfirmationEmail;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

class ClaimSubmittedListenerTest {

    private static final UUID EVENT_ID = UUID.fromString("6f1c2c1e-6a43-4c1e-9a0b-6e7d3f6c2a10");

    private final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
    private final ClaimConfirmationService confirmations = mock(ClaimConfirmationService.class);
    private final ClaimSubmittedListener listener =
            new ClaimSubmittedListener(confirmations, validatorFactory.getValidator());

    @AfterEach
    void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void handsTheEventToTheConfirmationService() {
        listener.onClaimSubmitted(event("ClaimSubmitted", 1, "jane.doe@example.com"));

        verify(confirmations).sendConfirmation(EVENT_ID, new ConfirmationEmail("jane.doe@example.com", "Jane",
                "HC-2026-000123", "RRM-****5678", "WATER", LocalDate.of(2026, 10, 12)));
    }

    @Test
    void rejectsAnUnknownEventVersionAsNonRetryable() {
        assertThatThrownBy(() -> listener.onClaimSubmitted(event("ClaimSubmitted", 2, "jane.doe@example.com")))
                .isInstanceOf(UnprocessableEventException.class)
                .isInstanceOf(AmqpRejectAndDontRequeueException.class) // rejected without requeue
                .hasMessage("Unsupported event ClaimSubmitted v2 (event " + EVENT_ID + ")");
        verifyNoInteractions(confirmations);
    }

    @Test
    void rejectsAnEventWithoutAVersion() {
        assertThatThrownBy(() -> listener.onClaimSubmitted(event("ClaimSubmitted", null, "jane.doe@example.com")))
                .isInstanceOf(UnprocessableEventException.class);
        verifyNoInteractions(confirmations);
    }

    @Test
    void rejectsAnotherEventType() {
        assertThatThrownBy(() -> listener.onClaimSubmitted(event("ClaimWithdrawn", 1, "jane.doe@example.com")))
                .isInstanceOf(UnprocessableEventException.class);
        verifyNoInteractions(confirmations);
    }

    @Test
    void rejectsAnInvalidPayloadWithoutQuotingTheRejectedValue() {
        assertThatThrownBy(() -> listener.onClaimSubmitted(event("ClaimSubmitted", 1, "jane.doe-at-example.com")))
                .isInstanceOf(UnprocessableEventException.class)
                .hasMessageContaining("data.policyholder.email")
                .hasMessageNotContaining("jane.doe-at-example.com");
        verifyNoInteractions(confirmations);
    }

    @Test
    void rejectsAnEventWithMissingData() {
        var event = new ClaimSubmittedEvent(EVENT_ID, "ClaimSubmitted", 1, null);

        assertThatThrownBy(() -> listener.onClaimSubmitted(event))
                .isInstanceOf(UnprocessableEventException.class)
                .hasMessageContaining("data must not be null");
        verifyNoInteractions(confirmations);
    }

    private static ClaimSubmittedEvent event(String type, Integer version, String email) {
        return new ClaimSubmittedEvent(EVENT_ID, type, version, new ClaimSubmittedEvent.Data(
                "HC-2026-000123", "RRM-12345678", new ClaimSubmittedEvent.Policyholder("Jane", email), "WATER",
                LocalDate.of(2026, 10, 12)));
    }
}
