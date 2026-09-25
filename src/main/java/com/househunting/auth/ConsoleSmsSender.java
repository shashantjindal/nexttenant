package com.househunting.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prints the message to the log instead of sending it.
 *
 * <p>A plain class, wired in {@link AuthConfig}. It was originally a
 * {@code @Component} with {@code @ConditionalOnMissingBean}, which does not
 * work: that annotation is only reliable on {@code @Bean} methods inside a
 * configuration class, where Spring can order the evaluation. On a scanned
 * component the ordering is undefined, and the bean silently never registers —
 * failing later as "no qualifying bean of type SmsSender".
 *
 * <p>It prints the code deliberately: in development the log IS the phone.
 * Which is also exactly why it must never run in production, where the code
 * would land in a log aggregator readable by far more people than the
 * recipient's handset.
 */
public class ConsoleSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSmsSender.class);

    @Override
    public void send(String phoneE164, String message) {
        log.info("""
                
                ┌───────────────────── SMS (not actually sent) ─────────────────────
                │ to:      {}
                │ message: {}
                └───────────────────────────────────────────────────────────────────
                """, phoneE164, message);
    }
}
