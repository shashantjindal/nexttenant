package com.househunting.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wiring for the auth package.
 */
@Configuration
@EnableConfigurationProperties(OtpProperties.class)
public class AuthConfig {

    /**
     * A Clock bean, so nothing in this package calls {@code Instant.now()}
     * directly.
     *
     * <p>Expiry, resend cooldowns and rate windows are all time-dependent. With
     * a hardcoded clock, testing "the code expired" means sleeping for ten
     * minutes, so in practice it never gets tested. Injecting the clock turns
     * time into an ordinary input.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The development SMS sender.
     *
     * <p>{@code @ConditionalOnProperty} with {@code matchIfMissing} rather than
     * {@code @ConditionalOnMissingBean}: the property is evaluated
     * deterministically, whereas "is another bean of this type defined?"
     * depends on registration order and produces intermittent wiring failures.
     *
     * <p>When a real provider arrives, it registers under
     * {@code househunting.sms.provider=msg91} and this one stands down.
     */
    @Bean
    @ConditionalOnProperty(name = "househunting.sms.provider",
                           havingValue = "console", matchIfMissing = true)
    public SmsSender consoleSmsSender() {
        return new ConsoleSmsSender();
    }
}
