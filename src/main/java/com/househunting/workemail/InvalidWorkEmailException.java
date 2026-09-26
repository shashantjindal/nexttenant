package com.househunting.workemail;

/**
 * The address was refused before any code was sent.
 *
 * <p>Unchecked, and carrying a message written for the person who typed it:
 * every throw site here is a validation failure whose only handling is to show
 * the message back on the form. A checked exception would put a catch block in
 * front of that with nothing useful to do in it.
 */
public class InvalidWorkEmailException extends RuntimeException {

    public InvalidWorkEmailException(String message) {
        super(message);
    }
}
