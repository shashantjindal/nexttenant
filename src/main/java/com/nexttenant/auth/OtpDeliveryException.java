package com.nexttenant.auth;

/**
 * The code could not be sent.
 *
 * <p>Separate from {@link OtpRateLimitedException}, which means "we refused to
 * send"; this means "we tried and the provider would not take it" — a rejected
 * credential, an unverified sender, a recipient outside a sandbox, a mail host
 * that is down.
 *
 * <p><b>Why this exists at all.</b> Without it, a send failure propagated out
 * of the controller as whatever the mail library threw, and Spring rendered a
 * Whitelabel error page: "There was an unexpected error (type=Internal Server
 * Error, status=500)". That tells the person nothing, tells the operator
 * nothing, and looks like the site is broken rather than the mail provider
 * being misconfigured.
 *
 * <p>The message here is deliberately vague about the cause. "Your address is
 * not verified in our sending sandbox" is true, useful to the operator, and an
 * invitation to probe for us — so the detail goes to the log and the visitor
 * gets something they can act on.
 */
public class OtpDeliveryException extends RuntimeException {
    public OtpDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
