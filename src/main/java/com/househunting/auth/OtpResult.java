package com.househunting.auth;

/**
 * The outcome of checking a code.
 *
 * <p>A returned value, not a thrown exception — and that is a correctness
 * decision, not a style one. The obvious implementation:
 *
 * <pre>
 *   &#64;Transactional
 *   public void verify(String code) {
 *       challenge.recordAttempt();               // attempts: 0 -> 1
 *       if (!matches(code)) {
 *           throw new OtpException("Wrong code");
 *       }
 *   }
 * </pre>
 *
 * <p>gives an attacker unlimited guesses. Spring rolls a transaction back on a
 * RuntimeException, so the increment is undone by the very exception reporting
 * the failure, and the counter never leaves zero. The attempt cap — the whole
 * reason a six-digit code is safe — silently does nothing.
 *
 * <p>{@code noRollbackFor} would patch it. Not throwing is better: a wrong code
 * is an expected answer, not an exceptional condition.
 *
 * <p>Sealed, so a {@code switch} over it is checked for exhaustiveness at
 * compile time. Adding a case later breaks every switch that does not handle
 * it — which is what you want when the missing case is a security decision.
 */
public sealed interface OtpResult {

    /** Correct code. Carries the number so the caller can create or find the account. */
    record Verified(String phoneE164) implements OtpResult {
    }

    /** Wrong code, budget remaining. */
    record WrongCode(int attemptsRemaining) implements OtpResult {
    }

    /** The code timed out. The user should request a new one. */
    record Expired() implements OtpResult {
    }

    /** Budget exhausted; the challenge has been burned. */
    record TooManyAttempts() implements OtpResult {
    }

    /**
     * No live challenge for this number.
     *
     * <p>Deliberately indistinguishable from an already-used code: telling the
     * two apart tells an attacker whether a code was recently issued.
     */
    record NoChallenge() implements OtpResult {
    }

    /** The submitted value was not six digits, so no lookup was attempted. */
    record Malformed() implements OtpResult {
    }

    default boolean isVerified() {
        return this instanceof Verified;
    }
}
