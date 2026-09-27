package com.househunting.auth;

import jakarta.servlet.http.HttpSession;

/**
 * Typed access to the handful of session attributes the login flow needs.
 *
 * <p>A wrapper rather than {@code session.setAttribute("subject", ...)}
 * scattered across the controller. String keys invite typos that produce null
 * at runtime instead of an error at compile time, and collecting them here
 * makes it possible to answer "what do we keep in the session, and when is it
 * cleared?" by reading one file.
 *
 * <p>The address lives HERE, in the session, and not in the database. That is
 * the whole reason an unauthenticated person's contact details are never
 * persisted — {@code otp_challenges} holds only a hash.
 */
public final class LoginFlow {

    private static final String SUBJECT = "hh.login.subject";
    private static final String MASKED = "hh.login.masked";
    private static final String CHANNEL = "hh.login.channel";

    /** Set between verifying a code and completing the profile of a new account. */
    private static final String PENDING_USER = "hh.login.pendingUserId";

    private LoginFlow() {
    }

    public static void startFor(HttpSession session, String subject, OtpDispatch dispatch) {
        session.setAttribute(SUBJECT, subject);
        session.setAttribute(MASKED, dispatch.maskedSubject());
        session.setAttribute(CHANNEL, dispatch.channel());
    }

    /** The address or number the code was sent to. */
    public static String subject(HttpSession session) {
        return (String) session.getAttribute(SUBJECT);
    }

    public static String maskedSubject(HttpSession session) {
        return (String) session.getAttribute(MASKED);
    }

    /**
     * How the code was delivered, so the page can say "email" rather than
     * guessing from whether the subject contains an {@code @}.
     */
    public static OtpChannel channel(HttpSession session) {
        Object value = session.getAttribute(CHANNEL);
        return value instanceof OtpChannel channel ? channel : OtpChannel.EMAIL;
    }

    public static boolean inProgress(HttpSession session) {
        return subject(session) != null;
    }

    // ---- The gap between verification and a usable account ----------------

    /**
     * Remembers a verified but incomplete account across the profile step.
     *
     * <p>Deliberately NOT a sign-in. The account exists at this point but has
     * no name and no number, and signing it in here would put a half-built
     * principal into every page that renders a user. The person is signed in
     * once the profile is saved, and not before.
     */
    public static void awaitProfile(HttpSession session, java.util.UUID userId) {
        session.setAttribute(PENDING_USER, userId);
    }

    public static java.util.UUID pendingUserId(HttpSession session) {
        Object value = session.getAttribute(PENDING_USER);
        return value instanceof java.util.UUID id ? id : null;
    }

    /**
     * Clears the flow.
     *
     * <p>Called on success and on abandonment. Leaving a verified address in
     * the session after login would keep it in the session store for the life
     * of the session, for no purpose.
     */
    public static void clear(HttpSession session) {
        session.removeAttribute(SUBJECT);
        session.removeAttribute(MASKED);
        session.removeAttribute(CHANNEL);
        session.removeAttribute(PENDING_USER);
    }
}
