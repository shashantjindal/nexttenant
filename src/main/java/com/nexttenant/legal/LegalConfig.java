package com.nexttenant.legal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link LegalProperties}.
 *
 * <p>A record annotated {@code @ConfigurationProperties} is not a bean by
 * itself — something has to enable it. Each feature does that for its own
 * properties here, next to the thing that uses them, rather than one list at
 * the application root that nobody remembers to add to.
 */
@Configuration
@EnableConfigurationProperties(LegalProperties.class)
public class LegalConfig {
}
