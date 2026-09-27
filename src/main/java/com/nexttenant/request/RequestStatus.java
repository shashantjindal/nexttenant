package com.nexttenant.request;

/**
 * The state of an interest request. Mirrors interest_requests_status_ck.
 *
 * <p>{@link #CONNECTED} is the only value that releases contact details, and
 * that fact is enforced in exactly one place: {@code ContactRelease}. Every
 * other state — including DECLINED and REVOKED — releases nothing.
 */
public enum RequestStatus {

    /** Sent; the leaver has not answered. */
    PENDING("Waiting for a reply"),

    /** Accepted. Both numbers are mutually readable, and only now. */
    CONNECTED("Connected"),

    /** The leaver said no. Nothing is released, then or later. */
    DECLINED("Declined"),

    /** The requester cancelled before an answer. */
    WITHDRAWN("Withdrawn"),

    /**
     * Access removed after the fact — by moderation, or by either party.
     *
     * <p>Separate from DECLINED so the history stays truthful: "we connected
     * and then it was revoked" is a different fact from "this was never
     * accepted", and the difference matters when reviewing a report.
     */
    REVOKED("Access removed");

    private final String label;

    RequestStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** The single condition under which contact details may be read. */
    public boolean releasesContact() {
        return this == CONNECTED;
    }

    /** The leaver can still answer. */
    public boolean isAwaitingReply() {
        return this == PENDING;
    }
}
