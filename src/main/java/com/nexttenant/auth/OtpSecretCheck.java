package com.nexttenant.auth;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Refuses to start outside development if the OTP secret is still the one
 * committed to the repository.
 *
 * <p>A secret in version control is not a secret. The usual failure is not
 * malice but forgetting: the dev default is convenient, deployment is busy, and
 * nothing complains — so every code in production is forgeable by anyone who
 * has read the repo.
 *
 * <p>Crashing at startup is the point. A warning in a log is read after the
 * incident; a refusal to boot is read during deployment.
 */
@Component
public class OtpSecretCheck implements ApplicationListener<ApplicationReadyEvent> {

    private final OtpProperties properties;
    private final Environment environment;

    public OtpSecretCheck(OtpProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        boolean devProfile = Arrays.asList(environment.getActiveProfiles()).contains("dev");

        if (properties.usingDevSecret() && !devProfile) {
            throw new IllegalStateException("""
                    Refusing to start: nexttenant.otp.secret is still the development default.
                    
                    Every OTP would be forgeable by anyone who can read this repository.
                    Set a long random value, for example:
                    
                        NEXTTENANT_OTP_SECRET=$(openssl rand -base64 48)
                    """);
        }
    }
}
