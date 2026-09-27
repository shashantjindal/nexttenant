package com.nexttenant.mail;

/**
 * Sends one email.
 *
 * <p>The same shape as {@code SmsSender}, and for the same reason: the thing
 * that decides WHAT to send should not know HOW. A service that talked to
 * Amazon SES directly could only be tested by talking to Amazon SES, and
 * swapping provider would mean editing the flow rather than editing a
 * provider.
 *
 * <p>Deliberately minimal. No attachments, no HTML alternative, no templating —
 * the product sends exactly one kind of email today. Widening this interface
 * before there is a second kind is guessing at a shape, and the guess is
 * usually wrong.
 */
public interface EmailSender {

    /**
     * @param to      a validated address
     * @param subject plain text, no newlines
     * @param body    plain text
     */
    void send(String to, String subject, String body);
}
