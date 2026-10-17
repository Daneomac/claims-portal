package com.claimsportal.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.claimsportal.api.domain.Address;
import com.claimsportal.api.domain.Claim;
import com.claimsportal.api.domain.ClaimNumber;
import com.claimsportal.api.domain.ClaimStatus;
import com.claimsportal.api.domain.ClaimSubmitted;
import com.claimsportal.api.domain.InvalidClaimException;
import com.claimsportal.api.domain.Loss;
import com.claimsportal.api.domain.LossDateRule;
import com.claimsportal.api.domain.LossType;
import com.claimsportal.api.domain.Policyholder;
import com.claimsportal.api.domain.Province;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClaimSubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T17:00:00Z");
    private static final ClaimNumber CLAIM_NUMBER = ClaimNumber.of(2026, 123);

    @Mock
    private ClaimNumberGenerator claimNumberGenerator;

    @Mock
    private ClaimRepository claims;

    @Mock
    private EventOutbox outbox;

    private ClaimSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new ClaimSubmissionService(claimNumberGenerator, claims, outbox,
                new LossDateRule(ZoneId.of("America/Winnipeg"), 365), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void savesTheClaimAndAppendsAMatchingEvent() {
        given(claimNumberGenerator.next()).willReturn(CLAIM_NUMBER);

        SubmittedClaim result = service.submit(command(LocalDate.of(2026, 10, 12)));

        ArgumentCaptor<Claim> savedClaim = ArgumentCaptor.forClass(Claim.class);
        ArgumentCaptor<ClaimSubmitted> appendedEvent = ArgumentCaptor.forClass(ClaimSubmitted.class);
        InOrder inOrder = inOrder(claims, outbox);
        inOrder.verify(claims).save(savedClaim.capture());
        inOrder.verify(outbox).append(appendedEvent.capture());

        Claim claim = savedClaim.getValue();
        assertThat(claim.claimNumber()).isEqualTo(CLAIM_NUMBER);
        assertThat(claim.policyNumber()).isEqualTo("RRM-12345678");
        assertThat(claim.status()).isEqualTo(ClaimStatus.RECEIVED);
        assertThat(claim.submittedAt()).isEqualTo(NOW);

        ClaimSubmitted event = appendedEvent.getValue();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.claimId()).isEqualTo(claim.id());
        assertThat(event.claimNumber()).isEqualTo(CLAIM_NUMBER);
        assertThat(event.policyNumber()).isEqualTo("RRM-12345678");
        assertThat(event.firstName()).isEqualTo("Jane");
        assertThat(event.email()).isEqualTo("jane.doe@example.com");
        assertThat(event.lossType()).isEqualTo(LossType.WATER);
        assertThat(event.lossDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(event.submittedAt()).isEqualTo(NOW);
        assertThat(event.occurredAt()).isEqualTo(NOW);

        assertThat(result).isEqualTo(new SubmittedClaim(CLAIM_NUMBER, ClaimStatus.RECEIVED, NOW));
    }

    @Test
    void appendsNothingWhenSavingFails() {
        given(claimNumberGenerator.next()).willReturn(CLAIM_NUMBER);
        willThrow(new IllegalStateException("database unavailable")).given(claims).save(any());

        assertThatThrownBy(() -> service.submit(command(LocalDate.of(2026, 10, 12))))
                .isInstanceOf(IllegalStateException.class);

        verify(outbox, never()).append(any());
    }

    @Test
    void savesNothingWhenTheLossDateIsInTheFuture() {
        given(claimNumberGenerator.next()).willReturn(CLAIM_NUMBER);

        assertThatThrownBy(() -> service.submit(command(LocalDate.of(2026, 10, 18))))
                .isInstanceOf(InvalidClaimException.class);

        verify(claims, never()).save(any());
        verify(outbox, never()).append(any());
    }

    private static SubmitClaimCommand command(LocalDate lossDate) {
        return new SubmitClaimCommand(
                new Policyholder("Jane", "Doe", "Jane.Doe@example.com", "204-555-0142"),
                "rrm-12345678",
                new Loss(lossDate, LossType.WATER, "Burst pipe in the basement flooded the rec room.",
                        new BigDecimal("8500.00")),
                new Address("123 Main St", null, "Winnipeg", Province.MB, "R3C 1A1"));
    }
}
