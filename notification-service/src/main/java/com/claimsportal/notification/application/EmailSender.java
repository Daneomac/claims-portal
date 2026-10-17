package com.claimsportal.notification.application;

/** Port for delivering the confirmation email; implemented over SMTP in the {@code email} package. */
public interface EmailSender {

    /**
     * Renders and sends the email, returning once the mail server has accepted it.
     *
     * @throws RuntimeException if the email could not be handed to the mail server (for
     *                          example, it's down); the caller rolls back and the message is
     *                          retried
     */
    void send(ConfirmationEmail email);
}
