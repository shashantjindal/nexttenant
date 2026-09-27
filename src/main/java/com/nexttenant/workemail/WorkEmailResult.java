package com.nexttenant.workemail;

/**
 * The outcome of checking a code.
 *
 * <p>A sealed interface rather than an exception, for the same reason as
 * {@code OtpResult}: throwing from inside a {@code @Transactional} method rolls
 * the transaction back, and the transaction is what was recording the failed
 * attempt. Throwing "wrong code" would therefore undo the attempt counter —
 * turning a five-guess limit into an unlimited one. The bug is silent and it is
 * the exact bug the counter exists to prevent.
 *
 * <p>Sealed, so a switch over it is exhaustive at compile time. If a seventh
 * outcome is ever added, every place that decides what to do about one stops
 * compiling — which is what you want when the missing case would be a security
 * decision.
 */
public sealed interface WorkEmailResult {

    /** The code matched. The badge is now earned. */
    record Verified(String emailAddress) implements WorkEmailResult {
    }

    /** Wrong, but they may try again. */
    record WrongCode(int attemptsRemaining) implements WorkEmailResult {
    }

    /** Not six digits. Distinguished so the message can say so. */
    record Malformed() implements WorkEmailResult {
    }

    /** The code was right once but its window has passed. */
    record Expired() implements WorkEmailResult {
    }

    /** The attempt budget is spent; the challenge is dead. */
    record TooManyAttempts() implements WorkEmailResult {
    }

    /** Nothing outstanding — a stale tab, or a code already used. */
    record NoChallenge() implements WorkEmailResult {
    }

    /**
     * Somebody else already verified this address.
     *
     * <p>Only reachable at the final step, because the uniqueness check has to
     * happen when the address is proven rather than when it is claimed —
     * otherwise typing an address would tell you whether it is already on the
     * site, which is an account-existence oracle over other people's employers.
     */
    record AlreadyUsed() implements WorkEmailResult {
    }
}
