package com.claimsportal.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.claimsportal.api.application.ClaimSubmissionService;
import com.claimsportal.api.application.SubmitClaimCommand;
import com.claimsportal.api.application.SubmittedClaim;
import com.claimsportal.api.domain.ClaimNumber;
import com.claimsportal.api.domain.ClaimStatus;
import com.claimsportal.api.domain.InvalidClaimException;
import com.claimsportal.api.domain.LossType;
import com.claimsportal.api.domain.Province;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(ClaimController.class)
@ExtendWith(OutputCaptureExtension.class)
class ClaimControllerTest {

    private static final String VALID_CLAIM = """
            {
              "policyholder": { "firstName": "Jane", "lastName": "Doe",
                                "email": "jane.doe@example.com", "phone": "204-555-0142" },
              "policyNumber": "RRM-12345678",
              "loss": { "date": "2026-10-12", "type": "WATER",
                        "description": "Burst pipe in the basement flooded the finished rec room.",
                        "estimatedAmount": 8500.00 },
              "propertyAddress": { "street": "123 Main St", "unit": null, "city": "Winnipeg",
                                   "province": "MB", "postalCode": "R3C 1A1" },
              "declarationAccepted": true
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClaimSubmissionService claimSubmissionService;

    /** The slice has no tracing set up; with no current span, no trace ID is added. */
    @MockitoBean
    private Tracer tracer;

    @Test
    void returns201WithTheClaimNumber() throws Exception {
        given(claimSubmissionService.submit(any())).willReturn(new SubmittedClaim(
                ClaimNumber.of(2026, 123), ClaimStatus.RECEIVED, Instant.parse("2026-10-17T17:00:00.123456Z")));

        postClaim(VALID_CLAIM)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.claimNumber").value("HC-2026-000123"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.submittedAt").value("2026-10-17T17:00:00.123456Z"));
    }

    @Test
    void mapsTheRequestOntoTheCommand() throws Exception {
        given(claimSubmissionService.submit(any())).willReturn(
                new SubmittedClaim(ClaimNumber.of(2026, 1), ClaimStatus.RECEIVED, Instant.now()));

        postClaim(VALID_CLAIM).andExpect(status().isCreated());

        ArgumentCaptor<SubmitClaimCommand> captor = ArgumentCaptor.forClass(SubmitClaimCommand.class);
        verify(claimSubmissionService).submit(captor.capture());
        SubmitClaimCommand command = captor.getValue();
        assertThat(command.policyholder().email()).isEqualTo("jane.doe@example.com");
        assertThat(command.policyNumber()).isEqualTo("RRM-12345678");
        assertThat(command.loss().date()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(command.loss().type()).isEqualTo(LossType.WATER);
        assertThat(command.loss().estimatedAmount()).isEqualByComparingTo(new BigDecimal("8500.00"));
        assertThat(command.propertyAddress().province()).isEqualTo(Province.MB);
        assertThat(command.propertyAddress().postalCode()).isEqualTo("R3C 1A1");
    }

    @Test
    void returns400WithAnErrorForEachInvalidField() throws Exception {
        String invalid = """
                {
                  "policyholder": { "firstName": "", "lastName": "Doe3",
                                    "email": "not-an-email", "phone": "12345" },
                  "policyNumber": "ABC-1",
                  "loss": { "date": null, "type": "WATER", "description": "Too short",
                            "estimatedAmount": 12.345 },
                  "propertyAddress": { "street": "123 Main St", "city": "Winnipeg",
                                       "province": "MB", "postalCode": "12345" },
                  "declarationAccepted": false
                }
                """;

        postClaim(invalid)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(ApiExceptionHandler.INVALID_CLAIM))
                .andExpect(jsonPath("$.errors", hasSize(10)))
                .andExpect(jsonPath("$.errors[?(@.field == 'policyholder.firstName')].message")
                        .value("Enter your first name"))
                .andExpect(jsonPath("$.errors[?(@.field == 'policyholder.lastName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'policyholder.email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'policyholder.phone')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'policyNumber')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'loss.date')].message").value("Enter the date of loss"))
                .andExpect(jsonPath("$.errors[?(@.field == 'loss.description')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'loss.estimatedAmount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'propertyAddress.postalCode')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'declarationAccepted')]").exists());

        verify(claimSubmissionService, never()).submit(any());
    }

    @Test
    void returns400WhenRequiredSectionsAreMissing() throws Exception {
        postClaim("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder(
                        "declarationAccepted", "loss", "policyNumber", "policyholder", "propertyAddress")));
    }

    @Test
    void returns400ForMalformedJsonWithoutLeakingInternals() throws Exception {
        postClaim("{ \"policyNumber\": ")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(ApiExceptionHandler.UNREADABLE_BODY))
                .andExpect(content().string(not(containsString("jackson"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void returns400NamingTheFieldWhenAValueHasTheWrongType() throws Exception {
        postClaim(VALID_CLAIM.replace("\"WATER\"", "\"EARTHQUAKE\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("loss.type"))
                .andExpect(content().string(not(containsString("LossType"))));
    }

    @Test
    void returns400WhenTheDomainRejectsTheClaim() throws Exception {
        given(claimSubmissionService.submit(any()))
                .willThrow(new InvalidClaimException("loss.date", "The date of loss can't be in the future"));

        postClaim(VALID_CLAIM)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("loss.date"))
                .andExpect(jsonPath("$.errors[0].message").value("The date of loss can't be in the future"));
    }

    @Test
    void returnsAGeneric500ForUnexpectedErrors() throws Exception {
        given(claimSubmissionService.submit(any()))
                .willThrow(new IllegalStateException("connection to db-internal-7 refused"));
        aRequestTracedAs("4bf92f3577b34da6a3ce929d0e0e4736");

        postClaim(VALID_CLAIM)
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"))
                .andExpect(jsonPath("$.detail").value(ApiExceptionHandler.UNEXPECTED_ERROR))
                .andExpect(jsonPath("$.traceId").value("4bf92f3577b34da6a3ce929d0e0e4736"))
                .andExpect(content().string(not(containsString("db-internal-7"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void doesNotLogPersonalInformation(CapturedOutput output) throws Exception {
        given(claimSubmissionService.submit(any()))
                .willThrow(new IllegalStateException("boom"));

        postClaim(VALID_CLAIM).andExpect(status().isInternalServerError());

        assertThat(output).doesNotContain("jane.doe@example.com", "204-555-0142", "Doe");
    }

    /** Makes the mocked tracer report a current span in the given trace. */
    private void aRequestTracedAs(String traceId) {
        Span span = mock();
        TraceContext context = mock();
        given(context.traceId()).willReturn(traceId);
        given(span.context()).willReturn(context);
        given(tracer.currentSpan()).willReturn(span);
    }

    private ResultActions postClaim(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
