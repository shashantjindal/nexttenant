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

    /**
     * Pattern for pulling the code out of the message for the banner.
     *
     * <p>The code is displayed separately from the message so it is easy to
     * find while testing. The message is still printed in full, because that is
     * what a real recipient would see and it needs to match the DLT-registered
     * template exactly.
     */
    private static final java.util.regex.Pattern CODE =
            java.util.regex.Pattern.compile("\\b(\\d{6})\\b");

    @Override
    public void send(String phoneE164, String message) {
        java.util.regex.Matcher matcher = CODE.matcher(message);
        String code = matcher.find() ? matcher.group(1) : "(no code found)";

        // Plain ASCII, deliberately. Box-drawing characters render as "?" on a
        // Windows console under the default code page, and this is a line
        // somebody reads dozens of times a day while testing.
        log.info("""

                ==================== SMS (not actually sent) ====================
                  to:   {}
                  CODE: {}
                  text: {}
                =================================================================
                """, phoneE164, code, message);
    }
}
