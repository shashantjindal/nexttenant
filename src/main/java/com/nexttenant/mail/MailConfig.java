package com.nexttenant.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Wiring for the mail package.
 *
 * <p>Exactly one {@link EmailSender} is registered, chosen by
 * {@code nexttenant.mail.provider}. The rest of the application depends on
 * the interface and never learns which one it got.
 */
@Configuration
@EnableConfigurationProperties({
        MailProperties.class,
        com.nexttenant.common.SiteProperties.class
})
public class MailConfig {

    /**
     * The development email sender.
     *
     * <p>{@code @ConditionalOnProperty} with {@code matchIfMissing} rather than
     * {@code @ConditionalOnMissingBean} — see {@code AuthConfig} for why the
     * latter does not work reliably here.
     */
    @Bean
    @ConditionalOnProperty(name = "nexttenant.mail.provider",
                           havingValue = "console", matchIfMissing = true)
    public EmailSender consoleEmailSender() {
        return new ConsoleEmailSender();
    }

    /**
     * The production sender.
     *
     * <p>Takes {@link JavaMailSender}, which Spring Boot auto-configures from
     * {@code spring.mail.*}. Those properties have no defaults worth using, so
     * a deployment that sets {@code provider=smtp} and forgets the host fails
     * at the first send rather than at startup — which is why the deployment
     * checklist ends with sending one real code to a real inbox.
     */
    @Bean
    @ConditionalOnProperty(name = "nexttenant.mail.provider", havingValue = "smtp")
    public EmailSender smtpEmailSender(JavaMailSender mailer, MailProperties config) {
        return new SmtpEmailSender(mailer, config);
    }
}
