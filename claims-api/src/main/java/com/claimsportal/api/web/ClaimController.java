package com.claimsportal.api.web;

import com.claimsportal.api.application.ClaimSubmissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepts claim submissions. There's deliberately no GET endpoint and no Location header:
 * without authentication, reading a claim back would expose customer data (PLAN section 4).
 */
@RestController
@RequestMapping("/api/v1/claims")
class ClaimController {

    private final ClaimSubmissionService claimSubmissionService;

    ClaimController(ClaimSubmissionService claimSubmissionService) {
        this.claimSubmissionService = claimSubmissionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SubmitClaimResponse submit(@Valid @RequestBody SubmitClaimRequest request) {
        return SubmitClaimResponse.from(claimSubmissionService.submit(request.toCommand()));
    }
}
