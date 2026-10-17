package com.claimsportal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole submit flow against a real Postgres (Testcontainers): HTTP request in, claim row
 * and outbox row out, with the Flyway schema and Hibernate mappings exercised for real. The
 * relay is switched off so the outbox row can be checked before anything publishes it; see
 * {@link OutboxPublishingIntegrationTest} for publishing.
 */
@SpringBootTest(properties = "claims.outbox.relay-enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ClaimSubmissionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void submittingAClaimStoresTheClaimAndAnOutboxEvent() throws Exception {
        LocalDate lossDate = LocalDate.now(ZoneId.of("America/Winnipeg")).minusDays(5);

        String response = mockMvc.perform(post("/api/v1/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimJson(lossDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimNumber").value(matchesPattern("HC-\\d{4}-\\d{6,}")))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andReturn().getResponse().getContentAsString();
        String claimNumber = jsonMapper.readTree(response).get("claimNumber").asString();

        Map<String, Object> claim = jdbcClient.sql("SELECT * FROM claim WHERE claim_number = ?")
                .param(claimNumber)
                .query().singleRow();
        assertThat(claim)
                .containsEntry("policy_number", "RRM-12345678")        // normalized from lower case
                .containsEntry("policyholder_first_name", "Jane")
                .containsEntry("policyholder_email", "jane.doe@example.com") // lower-cased
                .containsEntry("loss_type", "WATER")
                .containsEntry("property_province", "MB")
                .containsEntry("property_postal_code", "R3C 1A1")    // normalized
                .containsEntry("status", "RECEIVED");
        assertThat((BigDecimal) claim.get("estimated_amount")).isEqualByComparingTo("8500.00");

        Map<String, Object> event = jdbcClient.sql("SELECT * FROM outbox_event WHERE aggregate_id = ?")
                .param(claim.get("id"))
                .query().singleRow();
        assertThat(event)
                .containsEntry("event_type", "ClaimSubmitted")
                .containsEntry("event_version", 1)
                .containsEntry("attempts", 0)
                .containsEntry("published_at", null);

        JsonNode payload = jsonMapper.readTree(event.get("payload").toString());
        assertThat(payload.get("eventId").asString()).isEqualTo(((UUID) event.get("id")).toString());
        assertThat(payload.get("eventType").asString()).isEqualTo("ClaimSubmitted");
        assertThat(payload.get("eventVersion").asInt()).isEqualTo(1);
        JsonNode data = payload.get("data");
        assertThat(data.get("claimNumber").asString()).isEqualTo(claimNumber);
        assertThat(data.get("policyholder").get("email").asString()).isEqualTo("jane.doe@example.com");
        assertThat(data.get("lossDate").asString()).isEqualTo(lossDate.toString());
        // Only what the email needs: no description, phone or address (PLAN section 5)
        assertThat(data.has("description")).isFalse();
        assertThat(data.get("policyholder").has("phone")).isFalse();
        assertThat(data.has("propertyAddress")).isFalse();
    }

    @Test
    void aRejectedClaimStoresNothing() throws Exception {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("America/Winnipeg")).plusDays(1);
        long claimsBefore = count("claim");
        long eventsBefore = count("outbox_event");

        mockMvc.perform(post("/api/v1/claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimJson(tomorrow)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("loss.date"));

        assertThat(count("claim")).isEqualTo(claimsBefore);
        assertThat(count("outbox_event")).isEqualTo(eventsBefore);
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static String claimJson(LocalDate lossDate) {
        return """
                {
                  "policyholder": { "firstName": "Jane", "lastName": "Doe",
                                    "email": "Jane.Doe@Example.com", "phone": "204-555-0142" },
                  "policyNumber": "rrm-12345678",
                  "loss": { "date": "%s", "type": "WATER",
                            "description": "Burst pipe in the basement flooded the finished rec room.",
                            "estimatedAmount": 8500.00 },
                  "propertyAddress": { "street": "123 Main St", "unit": null, "city": "Winnipeg",
                                       "province": "MB", "postalCode": "r3c1a1" },
                  "declarationAccepted": true
                }
                """.formatted(lossDate);
    }
}
