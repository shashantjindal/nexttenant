package com.nexttenant.auth;

/**
 * Base type for every reason a code can be refused.
 *
 * <p>A single hierarchy so the controller can catch one type and show the
 * message, rather than enumerating cases it will forget to update.
 */
public class OtpException extends RuntimeException {
    public OtpException(String message) {
        super(message);
    }
}
