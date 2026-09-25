package com.househunting.moderation;

/** Why a listing was reported. Mirrors reports_reason_ck. */
public enum ReportReason {
    IS_A_BROKER("This is a broker", "The product exists to cut brokers out; this is the report that matters most."),
    ASKED_FOR_MONEY("Asked for money upfront", "Payment before a visit is the single most common scam here."),
    LISTING_INACCURATE("The listing is wrong", "Rent, deposit, photos or terms do not match reality."),
    ALREADY_RENTED("Already rented", "Still listed after the room has gone."),
    HARASSMENT("Harassment or abuse", "Behaviour towards you or others."),
    SPAM("Spam", "Not a genuine listing."),
    OTHER("Something else", null);

    private final String label;
    private final String help;

    ReportReason(String label, String help) {
        this.label = label;
        this.help = help;
    }

    public String getLabel() {
        return label;
    }

    public String getHelp() {
        return help;
    }
}
