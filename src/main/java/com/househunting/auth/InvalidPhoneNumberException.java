package com.househunting.auth;

/** The input was not a valid Indian mobile number. */
public class InvalidPhoneNumberException extends RuntimeException {
    public InvalidPhoneNumberException(String message) {
        super(message);
    }
}
