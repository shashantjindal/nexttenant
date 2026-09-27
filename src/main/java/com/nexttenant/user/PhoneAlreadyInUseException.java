package com.nexttenant.user;

/**
 * The mobile number offered at the profile step is already on another account.
 *
 * <p>"One number, one account" is enforced by user_private_phone_uq. This is
 * the friendly failure in front of it, so a collision is a message on a form
 * rather than a 500.
 *
 * <p>Its message deliberately does not say whose account holds the number —
 * that would make the profile form into a way of asking "is this person on
 * NextTenant?".
 */
public class PhoneAlreadyInUseException extends RuntimeException {
    public PhoneAlreadyInUseException(String message) {
        super(message);
    }
}
