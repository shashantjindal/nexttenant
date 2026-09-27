package com.nexttenant.auth;

import java.io.Serializable;
import java.util.UUID;

/**
 * Who is signed in, as stored in the security context.
 *
 * <p>Carries only what pages routinely need — the id, and the first name for
 * the header greeting. <strong>Not the phone number.</strong> The principal is
 * serialised into SPRING_SESSION_ATTRIBUTES on every request, so anything here
 * is written to the database continuously; a phone number in this record would
 * be a phone number in every session row.
 *
 * <p>Serializable because Spring Session stores it as bytes. The explicit
 * serialVersionUID matters: without one the JVM derives it from the class
 * structure, so adding a field would invalidate every live session and log
 * everybody out on deploy.
 */
public record AuthenticatedUser(UUID id, String firstName, boolean admin)
        implements Serializable, java.security.Principal {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * The id, as the security principal name.
     *
     * <p>Spring Session stores this in SPRING_SESSION.PRINCIPAL_NAME, which is
     * what makes revoking every session for one account a single DELETE. With
     * the default — the record's toString() — sessions could not be found by
     * user, and the claim that server-side sessions allow instant revocation
     * would be theoretical.
     */
    @Override
    public String getName() {
        return id.toString();
    }

    /** True once the profile step is done and the account is usable. */
    public boolean hasName() {
        return firstName != null && !firstName.isBlank();
    }
}
