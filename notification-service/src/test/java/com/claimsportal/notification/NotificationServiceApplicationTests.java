package com.claimsportal.notification;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The context starts, and the counters are exposed at {@code /actuator/prometheus}. Tests
 * switch metrics export off by default; {@code @AutoConfigureMetrics} turns it back on.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
class NotificationServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesTheNotificationCountersForPrometheus() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("notifications_sent_total")))
                .andExpect(content().string(containsString("notifications_failed_total")))
                .andExpect(content().string(containsString("notifications_duplicates_skipped_total")));
    }
}
