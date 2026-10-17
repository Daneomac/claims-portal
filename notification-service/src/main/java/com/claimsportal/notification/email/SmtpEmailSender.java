package com.claimsportal.notification.email;

import com.claimsportal.notification.application.ConfirmationEmail;
import com.claimsportal.notification.application.EmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Sends the confirmation through the SMTP server configured under {@code spring.mail.*}. */
@Component
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final ConfirmationEmailRenderer renderer;
    private final EmailProperties properties;

    SmtpEmailSender(JavaMailSender mailSender, ConfirmationEmailRenderer renderer, EmailProperties properties) {
        this.mailSender = mailSender;
        this.renderer = renderer;
        this.properties = properties;
    }

    /**
     * Sends a multipart message with a plain-text and an HTML version of the same content.
     * Mail clients show the HTML one, or the text one if they can't (or won't) show HTML.
     *
     * @throws org.springframework.mail.MailException if the mail server can't be reached or
     *                                                refuses the message
     */
    @Override
    public void send(ConfirmationEmail email) {
        RenderedEmail rendered = renderer.render(email);
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(email.recipient());
            helper.setSubject(rendered.subject());
            helper.setText(rendered.text(), rendered.html());
        } catch (MessagingException e) {
            throw new MailPreparationException("Could not build the email for claim " + email.claimNumber(), e);
        }
        mailSender.send(message);
    }
}
