package com.househunting.auth;

import jakarta.servlet.http.HttpSession;

/**
 * Typed access to the handful of session attributes the login flow needs.
 *
 * <p>A wrapper rather than {@code session.setAttribute("phone", ...)} scattered
 * across the controller. String keys invite typos that produce null at runtime
 * instead of an error at compile time, and collecting them here makes it
 * possible to answer "what do we keep in the session, and when is it cleared?"
 * by reading one file.
 *
 * <p>The phone number lives HERE, in the session, and not in the database. That
 * is the whole reason an unauthenticated person's number is never persisted.
 */
public final class LoginFlow {

    private static final String PHONE = "hh.login.phone";
    private static final String MASKED = "hh.login.masked";

    private LoginFlow() {
    }

    public static void startFor(HttpSession session, String phoneE164, String masked) {
        session.setAttribute(PHONE, phoneE164);
        session.setAttribute(MASKED, masked);
    }

    public static String phone(HttpSession session) {
        return (String) session.getAttribute(PHONE);
    }

    public static String maskedPhone(HttpSession session) {
        return (String) session.getAttribute(MASKED);
    }

    public static boolean inProgress(HttpSession session) {
        return phone(session) != null;
    }

    /**
     * Clears the flow.
     *
     * <p>Called on success and on abandonment. Leaving a verified number in the
     * session after login would keep it in the database for the life of the
     * session, for no purpose.
     */
    public static void clear(HttpSession session) {
        session.removeAttribute(PHONE);
        session.removeAttribute(MASKED);
    }
}
