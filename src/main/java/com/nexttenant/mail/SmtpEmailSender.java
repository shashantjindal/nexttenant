package com.nexttenant.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends real email over SMTP.
 *
 * <p>The production counterpart to {@link ConsoleEmailSender}, selected by
 * {@code nexttenant.mail.provider=smtp}. Everything else in the application
 * still talks to {@link EmailSender} and cannot tell the difference — which is
 * the entire reason that interface exists.
 *
 * <p><b>Why SMTP rather than a provider's SDK.</b> Amazon SES, Resend,
 * Postmark, Mailgun and Brevo all accept SMTP, so the provider becomes four
 * environment variables instead of a dependency and a rewrite. A vendor SDK
 * would add webhooks, templates and analytics this product does not use, in
 * exchange for being locked to one supplier on the day the launch is busiest.
 *
 * <p>Plain text, no HTML alternative. These are six-digit codes and two-line
 * notifications; an HTML email would be more to build, more to get wrong
 * across clients, and more likely to be filtered.
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailer;
    private final MailProperties config;

    public SmtpEmailSender(JavaMailSender mailer, MailProperties config) {
        this.mailer = mailer;
        this.config = config;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        // The From address must be one the provider has verified for this
        // domain. An unverified sender is rejected outright by SES, and
        // silently spam-foldered by everyone else — which is far worse,
        // because nothing appears to be wrong.
        message.setFrom(config.from());

        // Replies go somewhere a person reads, not to the sending address,
        // which is usually a no-reply mailbox nobody ever opens. People DO
        // reply to notification emails, and a reply into a void is a support
        // request that never arrives.
        if (config.replyTo() != null && !config.replyTo().isBlank()) {
            message.setReplyTo(config.replyTo());
        }

        mailer.send(message);

        // The recipient is NOT logged. A log aggregator is readable by more
        // people than an inbox is, and an address there is the leak the
        // public/private table split exists to prevent.
        log.debug("Email sent: {}", subject);
    }
}
