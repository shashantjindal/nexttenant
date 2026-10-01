package com.nexttenant.listing;

/**
 * What kind of building the flat is in.
 *
 * <p>In Mumbai this is not decoration. A gated society and a standalone
 * building differ in security, in whether a watchman logs visitors, in what
 * hours guests may come, and in how the building feels about tenants at all.
 * People ask before they agree to visit, so the listing should answer.
 *
 * <p>Mirrors {@code listings_flat_type_ck}.
 */
public enum FlatType {

    STANDALONE("Standalone building"),

    GATED_SOCIETY("Gated society");

    private final String label;

    FlatType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
