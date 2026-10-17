package com.claimsportal.api.application;

import com.claimsportal.api.domain.ClaimNumber;

/** Hands out a new, unique claim number each time it's called. */
public interface ClaimNumberGenerator {

    ClaimNumber next();
}
