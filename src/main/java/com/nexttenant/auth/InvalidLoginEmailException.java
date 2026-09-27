package com.nexttenant.auth;

/**
 * The address typed on the sign-in form could not be used.
 *
 * <p>Its message is written to be shown to the person who typed it, the same
 * contract as {@link InvalidPhoneNumberException}. Nothing here is a security
 * boundary — a rejected address has not been looked up, so nothing is revealed
 * about whether an account exists.
 */
public class InvalidLoginEmailException extends RuntimeException {
    public InvalidLoginEmailException(String message) {
        super(message);
    }
}
