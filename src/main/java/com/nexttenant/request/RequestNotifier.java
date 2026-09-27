package com.nexttenant.request;

import com.nexttenant.common.SiteProperties;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.mail.EmailSender;
import com.nexttenant.user.User;
import com.nexttenant.user.UserPrivate;
import com.nexttenant.user.UserPrivateRepository;
import com.nexttenant.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * Emails people about requests.
 *
 * <p>The badge on the header only works for somebody who opens the site. This
 * is for everybody else — and for the fact that a request answered three days
 * late is usually a room already taken.
 *
 * <p><b>Runs after the commit, never inside it.</b> See {@link RequestEvent}
 * for why. {@code AFTER_COMMIT} means the listener only fires for things that
 * actually happened, and a mail server that hangs delays an email rather than
 * holding a database transaction open.
 *
 * <p><b>What these emails deliberately do NOT contain: contact details.</b>
 * An email sits in an inbox for years and is completely outside
 * {@code revoke} — if a leaver later removes access, a phone number sent in a
 * notification still works, and there is nothing anybody can do about it. So
 * the emails name the other person and link to the page, where
 * {@code ContactRelease} decides, every time, whether the number may be read.
 * That is the whole point of having a single choke point, and a notification
 * that quietly routed around it would be the largest hole in this design.
 *
 * <p>First names are fine to include: {@code users.first_name} is public and
 * already shown to strangers on every listing card.
 *
 * <p>The bodies are plain ASCII — a hyphen rather than an em dash — for the
 * same reason {@code ConsoleSmsSender} is. In development these are read from
 * a Windows console, where anything outside the default code page arrives as a
 * "?", and this is text somebody reads while testing. Correct in a real mail
 * client either way; legible in both this way.
 */
@Component
public class RequestNotifier {

    private static final Logger log = LoggerFactory.getLogger(RequestNotifier.class);

    private final EmailSender email;
    private final UserRepository users;
    private final UserPrivateRepository userPrivates;
    private final ListingRepository listings;
    private final SiteProperties site;

    public RequestNotifier(EmailSender email,
                           UserRepository users,
                           UserPrivateRepository userPrivates,
                           ListingRepository listings,
                           SiteProperties site) {
        this.email = email;
        this.users = users;
        this.userPrivates = userPrivates;
        this.listings = listings;
        this.site = site;
    }

    /**
     * Somebody asked about a room; tell the leaver.
     *
     * <p>One email per request rather than a digest. At this volume a digest
     * would mean the first person to ask waits hours for the leaver to hear
     * about it, and speed is the entire value here. If a popular listing ever
     * makes this annoying, the fix is to batch AFTER the first — the first one
     * should always go immediately.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSent(RequestEvent.Sent event) {
        deliver(event, (recipient, listing, other) -> new Message(
                "%s wants your %s in %s".formatted(
                        nameOf(other), roomWord(listing), areaOf(listing)),
                """
                Hi %s,

                %s has asked about your %s in %s.

                Nothing of yours has been shared. They cannot see your number,
                and you cannot see theirs, unless you accept.

                Read it and reply here:
                %s

                -- NextTenant
                """.formatted(
                        nameOf(recipient),
                        nameOf(other),
                        roomWord(listing),
                        areaOf(listing),
                        site.url("/requests"))));
    }

    /**
     * A leaver accepted; tell the person who asked.
     *
     * <p>The subject line carries the outcome, because this is an email people
     * will read from a notification without opening it.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccepted(RequestEvent.Accepted event) {
        deliver(event, (recipient, listing, other) -> new Message(
                "%s accepted your request - you can message them now".formatted(nameOf(other)),
                """
                Hi %s,

                %s accepted your request for the %s in %s. You can both see
                each other's number now.

                Open the conversation here:
                %s

                Before you pay anything: visit in person first. Nobody on
                NextTenant should ask you for a deposit before you have seen
                the flat.

                -- NextTenant
                """.formatted(
                        nameOf(recipient),
                        nameOf(other),
                        roomWord(listing),
                        areaOf(listing),
                        site.url("/requests"))));
    }

    // ---- Plumbing ---------------------------------------------------------

    /** Subject and body, built once the three parties have been loaded. */
    private record Message(String subject, String body) {
    }

    @FunctionalInterface
    private interface Compose {
        Message build(User recipient, Listing listing, User other);
    }

    /**
     * Loads what a message needs, builds it and sends it.
     *
     * <p><b>Nothing here may throw.</b> This runs after the commit, so a
     * failure cannot roll anything back — it can only turn a successful
     * accept into a 500 on a page where the accept already happened, which is
     * the most confusing possible outcome. A missing address or an unreachable
     * mail server is logged and dropped; the badge on the header is the
     * fallback, and it is the reason there is a fallback.
     */
    private void deliver(RequestEvent event, Compose compose) {
        try {
            Optional<User> recipient = users.findById(event.recipientUserId());
            Optional<User> other = users.findById(event.otherUserId());
            Optional<Listing> listing = listings.findById(event.listingId());
            Optional<String> address = userPrivates.findById(event.recipientUserId())
                    .map(UserPrivate::getEmail);

            if (recipient.isEmpty() || other.isEmpty() || listing.isEmpty() || address.isEmpty()) {
                log.warn("Skipping notification for request {}: missing data", event.requestId());
                return;
            }

            Message message = compose.build(recipient.get(), listing.get(), other.get());
            email.send(address.get(), message.subject(), message.body());

            // The request id, never the address. A log line is readable by far
            // more people than an inbox is.
            log.debug("Notified about request {}", event.requestId());
        } catch (RuntimeException e) {
            log.error("Could not notify about request {}", event.requestId(), e);
        }
    }

    /** Falls back rather than printing "null" at somebody. */
    private static String nameOf(User user) {
        String name = user.getFirstName();
        return name == null || name.isBlank() ? "Someone" : name;
    }

    private static String roomWord(Listing listing) {
        return listing.getRoomType() == null
                ? "room"
                : listing.getRoomType().getLabel().toLowerCase(java.util.Locale.ENGLISH);
    }

    private static String areaOf(Listing listing) {
        String area = listing.getAreaName();
        return area == null || area.isBlank() ? "Mumbai" : area;
    }
}
