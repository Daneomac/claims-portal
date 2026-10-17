package com.claimsportal.api.application;

import com.claimsportal.api.domain.Address;
import com.claimsportal.api.domain.Loss;
import com.claimsportal.api.domain.Policyholder;

/** Everything the customer entered, already checked for shape by the web layer. */
public record SubmitClaimCommand(
        Policyholder policyholder,
        String policyNumber,
        Loss loss,
        Address propertyAddress) {

    /** Personal information must never reach a log, so only non-identifying details are printed. */
    @Override
    public String toString() {
        return "SubmitClaimCommand[lossType=" + loss.type() + ", province=" + propertyAddress.province() + "]";
    }
}
