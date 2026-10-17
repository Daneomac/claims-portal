package com.claimsportal.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.claimsportal.notification.application.ConfirmationEmail;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Renders the real templates with the same Thymeleaf engine the application uses. */
class ConfirmationEmailRendererTest {

    private final ConfirmationEmailRenderer renderer =
            new ConfirmationEmailRenderer(new EmailConfiguration().emailTemplateEngine());

    @Test
    void putsTheClaimNumberInTheSubject() {
        RenderedEmail email = renderer.render(confirmationFor("Jane"));

        assertThat(email.subject()).isEqualTo("We've received your claim HC-2026-000123");
    }

    @Test
    void greetsTheCustomerAndShowsTheClaimNumberInBothParts() {
        RenderedEmail email = renderer.render(confirmationFor("Jane"));

        assertThat(email.html()).contains("Hi <span>Jane</span>,", "HC-2026-000123");
        assertThat(email.text()).contains("Hi Jane,", "Your claim number: HC-2026-000123");
    }

    @Test
    void describesTheLossInWordsAndIncludesNextSteps() {
        RenderedEmail email = renderer.render(confirmationFor("Jane"));

        for (String body : new String[] {email.html(), email.text()}) {
            assertThat(body).contains("Water damage", "October 12, 2026",
                    "An adjuster will contact you within 2 business days.");
        }
    }

    @Test
    void showsOnlyTheMaskedPolicyNumber() {
        RenderedEmail email = renderer.render(confirmationFor("Jane"));

        assertThat(email.html()).contains("RRM-****5678").doesNotContain("12345678");
        assertThat(email.text()).contains("RRM-****5678").doesNotContain("12345678");
    }

    @Test
    void escapesTheFirstNameInTheHtmlPart() {
        RenderedEmail email = renderer.render(confirmationFor("<script>alert('hi')</script>"));

        assertThat(email.html())
                .contains("&lt;script&gt;alert(&#39;hi&#39;)&lt;/script&gt;")
                .doesNotContain("<script>");
    }

    @Test
    void leavesTheBodiesOutOfToString() {
        RenderedEmail email = renderer.render(confirmationFor("Jane"));

        assertThat(email.toString()).contains("HC-2026-000123").doesNotContain("Jane");
    }

    @ParameterizedTest
    @CsvSource({
            "FIRE, Fire",
            "WATER, Water damage",
            "WIND_HAIL, Wind or hail",
            "THEFT, Theft",
            "VANDALISM, Vandalism",
            "OTHER, Other",
            "SEWER_BACKUP, Sewer backup", // a type added to the contract later
    })
    void describesEachLossType(String code, String description) {
        assertThat(ConfirmationEmailRenderer.describeLossType(code)).isEqualTo(description);
    }

    private static ConfirmationEmail confirmationFor(String firstName) {
        return ConfirmationEmail.forClaim("jane.doe@example.com", firstName, "HC-2026-000123", "RRM-12345678",
                "WATER", LocalDate.of(2026, 10, 12));
    }
}
