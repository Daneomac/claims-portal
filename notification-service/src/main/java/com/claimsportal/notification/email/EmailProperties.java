package com.claimsportal.notification.email;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Email settings under {@code notifications.email.*} in application.yml. The SMTP server
 * itself is configured with Spring Boot's {@code spring.mail.*} properties.
 *
 * @param from the sender address on confirmation emails
 */
@Validated
@ConfigurationProperties("notifications.email")
public record EmailProperties(@NotBlank @Email String from) {
}
