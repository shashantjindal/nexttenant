package com.nexttenant.request;

import java.util.UUID;

/**
 * Something happened to a request that the other party should hear about.
 *
 * <p><b>Why an event rather than calling the mailer directly.</b>
 * {@code RequestService.send} and {@code accept} are {@code @Transactional}.
 * A mail call inside one of those would be wrong twice over:
 *
 * <ul>
 *   <li>the email goes out even if the transaction then rolls back, so
 *       somebody is told their request was accepted when it was not — and an
 *       email cannot be un-sent;</li>
 *   <li>a slow or unreachable mail server holds a database transaction open
 *       for as long as it takes to time out.</li>
 * </ul>
 *
 * <p>Publishing an event lets {@link RequestNotifier} run
 * {@code AFTER_COMMIT}, which is the only point at which the thing being
 * announced is definitely true.
 *
 * <p>These carry IDs, not entities. An entity published from inside a
 * transaction is read again after that transaction closed, and a lazy field
 * touched then throws {@code LazyInitializationException} — in the listener,
 * far from the code that caused it. IDs are re-read by the listener in its
 * own transaction, which also means it sees committed state rather than a
 * snapshot that might have been rolled back.
 */
public sealed interface RequestEvent {

    UUID requestId();

    UUID listingId();

    /** Who should be told. */
    UUID recipientUserId();

    /**
     * The party the recipient is being told ABOUT.
     *
     * <p>On the interface rather than worked out by the listener, so there is
     * no {@code instanceof} chain deciding who is who — the kind of code where
     * a new event type compiles fine and quietly emails the wrong person.
     */
    UUID otherUserId();

    /**
     * Somebody asked about a room. The LEAVER is told.
     *
     * <p>This is the notification that matters most to the product working at
     * all: a request nobody notices is how both sides conclude the site is
     * dead.
     */
    record Sent(UUID requestId, UUID listingId, UUID requesterUserId, UUID leaverUserId)
            implements RequestEvent {

        @Override
        public UUID recipientUserId() {
            return leaverUserId;
        }

        @Override
        public UUID otherUserId() {
            return requesterUserId;
        }
    }

    /**
     * A leaver accepted. The REQUESTER is told.
     *
     * <p>The one moment in this product where contact becomes readable, so it
     * is also the moment somebody most wants to know without having to check.
     */
    record Accepted(UUID requestId, UUID listingId, UUID requesterUserId, UUID leaverUserId)
            implements RequestEvent {

        @Override
        public UUID recipientUserId() {
            return requesterUserId;
        }

        @Override
        public UUID otherUserId() {
            return leaverUserId;
        }
    }
}
