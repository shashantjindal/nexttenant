package com.nexttenant.seo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Verification and analytics, bound from {@code nexttenant.seo.*}.
 *
 * <p>Both are deployment facts rather than code: the Search Console token
 * belongs to whoever owns the domain, and the analytics host is a service that
 * may or may not be running. A fork or a staging copy must not verify itself as
 * production or report its own traffic into production's dashboard, which is
 * what hardcoding either into a template would cause.
 *
 * <p><b>On the analytics choice.</b> This is shaped for Plausible or Umami —
 * one script, one {@code data-domain}, no cookies, no cross-site identifier and
 * no personal data leaving the country. Under India's DPDP Act that is the
 * difference between analytics you can simply run and analytics that need a
 * consent banner in front of them. Google Analytics would need the banner, and
 * at this size a banner costs more than the numbers are worth. If that trade
 * ever changes, the decision belongs in a conversation, not in this file.
 *
 * @param searchConsoleToken the value from Search Console's "HTML tag" method,
 *                           without the surrounding markup
 * @param analyticsScriptUrl absolute URL of the analytics script; blank disables
 *                           analytics entirely
 * @param analyticsDomain    the site identifier the analytics host knows us by,
 *                           usually the bare domain
 */
@ConfigurationProperties(prefix = "nexttenant.seo")
public record SeoProperties(
        String searchConsoleToken,
        String analyticsScriptUrl,
        String analyticsDomain
) {

    public SeoProperties {
        searchConsoleToken = blankToNull(searchConsoleToken);
        analyticsScriptUrl = blankToNull(analyticsScriptUrl);
        analyticsDomain = blankToNull(analyticsDomain);
    }

    public boolean hasSearchConsoleToken() {
        return searchConsoleToken != null;
    }

    /**
     * True only when BOTH halves are configured.
     *
     * <p>A script URL with no domain loads the tracker and reports nothing,
     * which is the worst of the three states: a third-party request on every
     * page view, bought for no data. Requiring both means a half-finished
     * configuration is simply off.
     */
    public boolean hasAnalytics() {
        return analyticsScriptUrl != null && analyticsDomain != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
