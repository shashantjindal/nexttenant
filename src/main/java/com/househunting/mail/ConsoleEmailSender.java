package com.househunting.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prints the message to the log instead of sending it.
 *
 * <p>A plain class, wired in {@link MailConfig} rather than annotated
 * {@code @Component} with {@code @ConditionalOnMissingBean}. That annotation is
 * only reliable on {@code @Bean} methods inside a configuration class, where
 * Spring controls the evaluation order; on a scanned component the ordering is
 * undefined and the bean silently never registers — surfacing much later as
 * "no qualifying bean of type EmailSender". We learned that one the hard way
 * with {@code ConsoleSmsSender}.
 *
 * <p>It prints the code deliberately: in development the log IS the inbox.
 * Which is exactly why it must never run in production, where the code would
 * land in a log aggregator readable by far more people than the recipient.
 */
public class ConsoleEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        // Plain ASCII, deliberately. Box-drawing characters render as "?" on a
        // Windows console under the default code page, and this is a line
        // somebody reads dozens of times a day while testing.
        log.info("""

                =================== EMAIL (not actually sent) ===================
                  to:      {}
                  subject: {}
                -----------------------------------------------------------------
                {}
                =================================================================
                """, to, subject, body);
    }
}
