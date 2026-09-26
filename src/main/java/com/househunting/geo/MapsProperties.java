package com.househunting.geo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which map provider the browser should use, and the key it needs.
 *
 * <p>A key is configuration, not code. Committing a Google Maps key would put
 * it in every clone of the repository and in the build log of every CI run, and
 * a Maps key is billed per load — a leaked one is somebody else's invoice.
 *
 * <p>The key reaches the browser regardless, because the Maps JavaScript API
 * runs there; that is unavoidable and is why Google's own protection is an HTTP
 * referrer restriction set on the key in Cloud Console, not secrecy. Restrict
 * the key to your own hostnames and a copy taken from the page source is
 * useless elsewhere. Keeping it out of the repository is still worth doing:
 * it keeps the key rotatable without a deploy, and keeps development keys apart
 * from production ones.
 *
 * <p>When no key is set the templates fall back to Leaflet with OpenStreetMap
 * tiles, so a fresh clone runs with maps working and nothing to sign up for.
 */
@ConfigurationProperties(prefix = "househunting.maps")
public record MapsProperties(String googleApiKey) {

    /**
     * True when a Google key is configured.
     *
     * <p>Templates branch on this. A missing key does not disable maps, it
     * chooses the other provider — a page whose map silently vanishes because
     * of an unset environment variable is far harder to diagnose than one that
     * quietly renders OpenStreetMap.
     */
    public boolean hasGoogle() {
        return googleApiKey != null && !googleApiKey.isBlank();
    }

    /**
     * The Maps JavaScript API loader URL for a given init function.
     *
     * <p>Built here rather than concatenated in a Thymeleaf expression for two
     * reasons. The key is URL-encoded once, in Java, where that is a method
     * call rather than a thing to remember. And an ampersand inside an HTML
     * attribute has to be written {@code &amp;} — a template that concatenates
     * a query string produces a URL that happens to work in every browser's
     * error recovery and is not valid HTML, which is the kind of thing that
     * holds until the day something parses the page strictly.
     *
     * <p>{@code loading=async} opts into Google's non-blocking bootstrap;
     * without it the console warns on every page load.
     *
     * @param callback the name of a function on {@code window} that Google
     *                 calls once the API is ready
     */
    public String loaderUrl(String callback) {
        return "https://maps.googleapis.com/maps/api/js"
                + "?key=" + encode(googleApiKey)
                + "&callback=" + encode(callback)
                + "&loading=async";
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
