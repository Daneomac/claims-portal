package com.claimsportal.api.application;

import com.claimsportal.api.domain.Claim;

/** Stores claims. Implemented with JPA in the infrastructure layer. */
public interface ClaimRepository {

    void save(Claim claim);
}
