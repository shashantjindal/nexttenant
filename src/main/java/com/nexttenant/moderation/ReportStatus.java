package com.nexttenant.moderation;

/**
 * Where a report is in review. Mirrors reports_status_ck.
 *
 * <p>Only CONFIRMED counts towards the two-strikes rule. A report sitting in
 * OPEN has had no human judgement applied to it, and letting unreviewed reports
 * remove accounts would turn moderation into a weapon anyone could pick up.
 */
public enum ReportStatus {
    OPEN("Awaiting review"),
    CONFIRMED("Confirmed"),
    DISMISSED("Dismissed");

    private final String label;

    ReportStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Only a confirmed report counts against an account. */
    public boolean countsAgainstAccount() {
        return this == CONFIRMED;
    }
}
