package com.househunting.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who mail comes from, bound from {@code househunting.mail.*}.
 *
 * <p>The SMTP host, port and credentials are NOT here — those are Spring
 * Boot's own {@code spring.mail.*} and it already knows how to bind them.
 * Re-declaring them would mean two sources of truth for the same connection.
 * This holds only what is ours to decide: the addresses on the message.
 *
 * @param provider "console" in development, "smtp" in production
 * @param from     a verified sender on the sending domain
 * @param replyTo  a mailbox a person actually reads; optional
 */
@ConfigurationProperties(prefix = "househunting.mail")
public record MailProperties(String provider, String from, String replyTo) {

    public MailProperties {
        provider = provider == null || provider.isBlank() ? "console" : provider;

        // A default that is obviously non-functional rather than plausible.
        // .invalid can never resolve (RFC 2606), so a deployment that forgot
        // to set this fails visibly at the provider instead of sending mail
        // that quietly lands nowhere.
        from = from == null || from.isBlank() ? "NextTenant <noreply@invalid.invalid>" : from;
    }
}
