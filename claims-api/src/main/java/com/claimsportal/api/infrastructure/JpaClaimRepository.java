package com.claimsportal.api.infrastructure;

import com.claimsportal.api.application.ClaimRepository;
import com.claimsportal.api.domain.Claim;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/**
 * Saves claims with JPA. Claims are only ever inserted, so this calls {@code persist}
 * directly (a Spring Data {@code save} would first SELECT to check whether the row exists,
 * because the ID is assigned by the application).
 */
@Repository
class JpaClaimRepository implements ClaimRepository {

    private final EntityManager entityManager;

    JpaClaimRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void save(Claim claim) {
        entityManager.persist(ClaimEntity.from(claim));
    }
}
