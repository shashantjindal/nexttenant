package com.househunting.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the mail package.
 */
@Configuration
public class MailConfig {

    /**
     * The development email sender.
     *
     * <p>{@code @ConditionalOnProperty} with {@code matchIfMissing} rather than
     * {@code @ConditionalOnMissingBean} — see {@code AuthConfig} for why the
     * latter does not work reliably here.
     *
     * <p>When a real provider arrives it registers under
     * {@code househunting.mail.provider=ses} (or whichever) and this one stands
     * down. The switch is a property, not a code change, so the same jar runs
     * in both places.
     */
    @Bean
    @ConditionalOnProperty(name = "househunting.mail.provider",
                           havingValue = "console", matchIfMissing = true)
    public EmailSender consoleEmailSender() {
        return new ConsoleEmailSender();
    }
}
