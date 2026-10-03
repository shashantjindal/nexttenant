package com.nexttenant.listing.wizard;

import java.util.UUID;

/**
 * Who is driving the listing flow.
 *
 * <p>There are two doors into the same screens now. A leaver fills in their
 * own listing at {@code /list/...}; an operator fills one in for somebody
 * else at {@code /admin/listings/...}, because the fastest way to seed a
 * marketplace is to take the details over WhatsApp and type them in.
 *
 * <p>This exists so that difference is stated once, at the one place that
 * decides whether an edit is allowed, instead of becoming a second
 * authorization path that drifts from the first. A boolean parameter called
 * {@code admin} threaded through four service methods would do the same job
 * and would be unreadable at every call site — {@code save(id, userId, true)}
 * says nothing. {@code Actor.admin(id)} says what it means.
 *
 * <p><b>The id is always the real person acting</b>, never the account being
 * acted for. An admin filling in Priya's listing is {@code Actor.admin(myId)},
 * not {@code Actor.owner(priyaId)} — so if this ever needs to be written to an
 * audit trail, the field already holds the right name.
 */
public record Actor(UUID userId, boolean admin) {

    /** The person whose listing it is. */
    public static Actor owner(UUID userId) {
        return new Actor(userId, false);
    }

    /** An operator listing on somebody else's behalf. */
    public static Actor admin(UUID userId) {
        return new Actor(userId, true);
    }
}
