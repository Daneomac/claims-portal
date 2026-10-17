package com.claimsportal.notification.email;

/**
 * A rendered email, ready to send.
 *
 * @param subject the subject line
 * @param text    the plain-text body
 * @param html    the HTML body
 */
record RenderedEmail(String subject, String text, String html) {

    /** The bodies greet the customer by name, so they must never reach a log. */
    @Override
    public String toString() {
        return "RenderedEmail[subject=" + subject + ", bodies redacted]";
    }
}
