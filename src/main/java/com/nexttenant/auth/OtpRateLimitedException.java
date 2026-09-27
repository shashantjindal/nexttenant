package com.nexttenant.auth;

import java.time.Duration;

/** Too many codes requested. Carries how long until the caller may retry. */
public class OtpRateLimitedException extends OtpException {

    private final Duration retryAfter;

    public OtpRateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
