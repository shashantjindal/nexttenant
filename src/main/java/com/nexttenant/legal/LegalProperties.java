package com.nexttenant.legal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who to write to about personal data, and who is answerable for it.
 *
 * <p>Configuration rather than words in a template because these are the two
 * things in the privacy policy that MUST be true of whoever is actually
 * running the site. A fork, a staging copy or a handover to somebody else
 * changes them, and a policy naming the wrong person is worse than one naming
 * nobody — it tells a reader their complaint went somewhere it did not.
 *
 * <p>India's Digital Personal Data Protection Act requires a named grievance
 * officer with contact details published where a user can find them. That is
 * the reason {@code grievanceOfficer} exists separately from the address: an
 * inbox is not a person, and the Act asks for a person.
 *
 * @param contactEmail     where privacy requests and complaints arrive
 * @param grievanceOfficer the named individual answerable for them
 * @param entityName       who operates the site, as it should appear in the policy
 */
@ConfigurationProperties(prefix = "nexttenant.legal")
public record LegalProperties(
        String contactEmail,
        String grievanceOfficer,
        String entityName
) {

    public LegalProperties {
        contactEmail = blankToNull(contactEmail);
        grievanceOfficer = blankToNull(grievanceOfficer);
        entityName = entityName == null || entityName.isBlank() ? "NextTenant" : entityName.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * True once a real person has been named.
     *
     * <p>The page says so plainly when they have not, rather than printing an
     * empty line where a name belongs. An incomplete policy that admits it is
     * incomplete is honest; one with a gap dressed up as a layout is not.
     */
    public boolean hasGrievanceOfficer() {
        return grievanceOfficer != null && contactEmail != null;
    }
}
