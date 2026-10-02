package com.nexttenant.guide;

/** Whether a guide is visible to the public. Mirrors guides_status_ck. */
public enum GuideStatus {

    /** Being written. Visible only in the admin area. */
    DRAFT("Draft"),

    /** Live at its URL, in the sitemap, and in the guides index. */
    PUBLISHED("Published");

    private final String label;

    GuideStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
