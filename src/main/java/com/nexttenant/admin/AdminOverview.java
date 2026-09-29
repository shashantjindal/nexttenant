package com.nexttenant.admin;

/**
 * The numbers on the admin landing page.
 *
 * <p>Chosen to answer the two questions somebody running this site actually
 * has: <em>is anything waiting for me?</em> and <em>is the marketplace
 * turning over?</em> Everything here earns its place against one of those.
 *
 * <p>Deliberately not a general analytics page. Page views, conversion funnels
 * and time-series charts are a different job, and a dashboard that tries to be
 * one stops being glanceable — which is the only thing this needs to be.
 *
 * @param users          accounts that exist
 * @param usersThisWeek  accounts created in the last seven days
 * @param liveListings   rooms currently findable in search
 * @param draftListings  wizards somebody started and did not finish
 * @param rentedListings rooms marked taken — the closest thing to a success
 * @param pendingRequests people waiting on a leaver to answer
 * @param connections    requests accepted, so two people exchanged numbers
 * @param openReports    moderation waiting on you
 */
public record AdminOverview(
        long users,
        long usersThisWeek,
        long liveListings,
        long draftListings,
        long rentedListings,
        long pendingRequests,
        long connections,
        long openReports
) {

    /**
     * Whether anything needs a person.
     *
     * <p>Drives the one piece of emphasis on the page. A dashboard where
     * everything looks equally important is one nobody reads twice.
     */
    public boolean needsAttention() {
        return openReports > 0;
    }

    /**
     * Drafts as a share of everything started.
     *
     * <p>Worth watching rather than worth acting on immediately: a high figure
     * means people are opening the wizard and giving up, and the step they
     * abandon is the thing to fix. Returns 0 rather than dividing by zero on
     * a site with nothing in it yet.
     */
    public long draftPercentage() {
        long started = liveListings + draftListings + rentedListings;
        return started == 0 ? 0 : Math.round(draftListings * 100.0 / started);
    }
}
