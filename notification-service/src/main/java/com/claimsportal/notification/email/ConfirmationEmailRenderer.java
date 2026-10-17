package com.claimsportal.notification.email;

import com.claimsportal.notification.application.ConfirmationEmail;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/** Turns a {@link ConfirmationEmail} into a subject, a plain-text body and an HTML body. */
@Component
class ConfirmationEmailRenderer {

    private static final String TEMPLATE = "claim-confirmation";
    private static final Locale LOCALE = Locale.CANADA;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, uuuu", LOCALE);

    private final ITemplateEngine templateEngine;

    ConfirmationEmailRenderer(ITemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    RenderedEmail render(ConfirmationEmail email) {
        Context context = new Context(LOCALE, Map.of(
                "firstName", email.firstName(),
                "claimNumber", email.claimNumber(),
                "policyNumber", email.maskedPolicyNumber(),
                "lossType", describeLossType(email.lossType()),
                "lossDate", DATE_FORMAT.format(email.lossDate())));

        return new RenderedEmail(
                "We've received your claim " + email.claimNumber(),
                templateEngine.process(TEMPLATE + ".txt", context),
                templateEngine.process(TEMPLATE + ".html", context));
    }

    /** The contract's loss type codes, as a customer would say them. */
    static String describeLossType(String lossType) {
        return switch (lossType) {
            case "FIRE" -> "Fire";
            case "WATER" -> "Water damage";
            case "WIND_HAIL" -> "Wind or hail";
            case "THEFT" -> "Theft";
            case "VANDALISM" -> "Vandalism";
            case "OTHER" -> "Other";
            // A type added to the contract after this service was built (an additive change
            // within v1): a readable form of the code is better than no email at all
            default -> {
                String words = lossType.replace('_', ' ').toLowerCase(Locale.ROOT);
                yield words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
            }
        };
    }
}
