package com.nexttenant.common;

/**
 * The numbers on the header's badges.
 *
 * <p>One object rather than one model attribute per badge. The wishlist count
 * arrived first and carried a note saying that if the header ever grew a second
 * badge they should be fetched together rather than as separate advice methods
 * — this is that second badge, and this is that.
 *
 * <p><b>What "together" bought, and what it did not.</b> It is still two
 * queries; what it removes is the sprawl. There is now one place that decides
 * what the chrome needs, one attribute for templates to read, and one obvious
 * spot to merge the two statements into a single round trip if the header ever
 * becomes hot enough to care. Merging them today would mean a raw statement in
 * {@code common} reading both {@code shortlists} and {@code interest_requests},
 * which trades a clean package boundary for two indexed counts on small tables.
 * That is not a trade worth making yet, and writing down why is cheaper than
 * rediscovering it.
 *
 * <p>Neither query runs for a signed-out visitor, which is most of them.
 *
 * @param wishlist        rooms the viewer has hearted
 * @param pendingRequests people waiting on the viewer to accept or decline
 * @param answeredUnseen  answers the viewer has been given and not read
 */
public record HeaderCounts(long wishlist, long pendingRequests, long answeredUnseen) {

    /** For signed-out visitors. Saves every template a null check. */
    public static final HeaderCounts NONE = new HeaderCounts(0, 0, 0);

    /**
     * The number on the Requests badge.
     *
     * <p>Both sides of the same page, added together, because the badge means
     * one thing: "there is something here for you". Splitting it into two
     * badges would make somebody read two numbers and work out that they both
     * lead to the same screen.
     *
     * <p>They stay separate in this record because the SENTENCES differ —
     * "2 people want your room" and "your request was accepted" are not the
     * same news, and the landing page says the right one.
     */
    public long requestsBadge() {
        return pendingRequests + answeredUnseen;
    }

    public boolean hasRequestsBadge() {
        return requestsBadge() > 0;
    }

    public boolean hasAnsweredUnseen() {
        return answeredUnseen > 0;
    }

    /** "reply" or "replies" — the noun for the requester's side. */
    public String answeredNoun() {
        return answeredUnseen == 1 ? "reply" : "replies";
    }

    /**
     * Named {@code hasX} rather than {@code isX}.
     *
     * <p>A boolean {@code isWishlist()} would be resolved by SpEL as the
     * property {@code wishlist} in preference to the record accessor of the
     * same name, so {@code ${counts.wishlist}} would silently render
     * {@code true} instead of a number. That exact shadowing already cost a
     * bug in {@code SearchForm}; {@code hasX} cannot collide, because the
     * property it implies is {@code hasWishlist}.
     */
    public boolean hasWishlist() {
        return wishlist > 0;
    }

    public boolean hasPendingRequests() {
        return pendingRequests > 0;
    }

    /**
     * "person" or "people", for a sentence that has to read correctly at one.
     *
     * <p>Lives here rather than as a ternary in the template because two
     * templates now need it, and "1 people want your room" is the kind of
     * detail that makes a site feel unfinished.
     */
    public String pendingNoun() {
        return pendingRequests == 1 ? "person" : "people";
    }

    public String pendingVerb() {
        return pendingRequests == 1 ? "wants" : "want";
    }
}
