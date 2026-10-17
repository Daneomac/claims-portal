package com.claimsportal.api.infrastructure;

import com.claimsportal.api.domain.Claim;
import com.claimsportal.api.domain.ClaimStatus;
import com.claimsportal.api.domain.LossType;
import com.claimsportal.api.domain.Province;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code claim} table. Kept separate from the domain {@link Claim} so the domain has
 * no persistence annotations; {@link #from} copies a claim into a row.
 */
@Entity
@Table(name = "claim")
class ClaimEntity {

    @Id
    private UUID id;

    @Column(name = "claim_number", nullable = false, unique = true)
    private String claimNumber;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "policyholder_first_name", nullable = false)
    private String policyholderFirstName;

    @Column(name = "policyholder_last_name", nullable = false)
    private String policyholderLastName;

    @Column(name = "policyholder_email", nullable = false)
    private String policyholderEmail;

    @Column(name = "policyholder_phone")
    private String policyholderPhone;

    @Column(name = "loss_date", nullable = false)
    private LocalDate lossDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "loss_type", nullable = false)
    private LossType lossType;

    @Column(name = "loss_description", nullable = false)
    private String lossDescription;

    @Column(name = "estimated_amount", precision = 10, scale = 2)
    private BigDecimal estimatedAmount;

    @Column(name = "property_street", nullable = false)
    private String propertyStreet;

    @Column(name = "property_unit")
    private String propertyUnit;

    @Column(name = "property_city", nullable = false)
    private String propertyCity;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_province", nullable = false)
    private Province propertyProvince;

    @Column(name = "property_postal_code", nullable = false)
    private String propertyPostalCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    /** Required by JPA. */
    protected ClaimEntity() {
    }

    static ClaimEntity from(Claim claim) {
        ClaimEntity entity = new ClaimEntity();
        entity.id = claim.id();
        entity.claimNumber = claim.claimNumber().value();
        entity.policyNumber = claim.policyNumber();
        entity.policyholderFirstName = claim.policyholder().firstName();
        entity.policyholderLastName = claim.policyholder().lastName();
        entity.policyholderEmail = claim.policyholder().email();
        entity.policyholderPhone = claim.policyholder().phone();
        entity.lossDate = claim.loss().date();
        entity.lossType = claim.loss().type();
        entity.lossDescription = claim.loss().description();
        entity.estimatedAmount = claim.loss().estimatedAmount();
        entity.propertyStreet = claim.propertyAddress().street();
        entity.propertyUnit = claim.propertyAddress().unit();
        entity.propertyCity = claim.propertyAddress().city();
        entity.propertyProvince = claim.propertyAddress().province();
        entity.propertyPostalCode = claim.propertyAddress().postalCode();
        entity.status = claim.status();
        entity.submittedAt = claim.submittedAt();
        return entity;
    }

    @Override
    public String toString() {
        return "ClaimEntity[" + claimNumber + "]";
    }
}
