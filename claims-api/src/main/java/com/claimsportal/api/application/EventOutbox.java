package com.claimsportal.api.application;

import com.claimsportal.api.domain.ClaimSubmitted;

/**
 * Records an event to be published later (transactional outbox, ADR 0002). The caller's
 * transaction must be active, so the event is stored if and only if the claim is.
 */
public interface EventOutbox {

    void append(ClaimSubmitted event);
}
