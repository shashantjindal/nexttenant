package com.househunting.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Facts about where this site is running, bound from {@code househunting.site.*}.
 *
 * <p>Exists because an email has to contain an absolute link. Everything
 * rendered in a browser can use a relative path — the browser knows where it
 * came from — but an inbox does not, so "/requests" in an email is a dead
 * link. This is the one place that knows the host, rather than the string
 * being hardcoded in whichever class happens to send mail.
 *
 * @param baseUrl scheme and host with no trailing slash, e.g. https://househunting.in
 */
@ConfigurationProperties(prefix = "househunting.site")
public record SiteProperties(String baseUrl) {

    public SiteProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl;

        // A trailing slash here becomes "//requests" in every link. Harmless
        // on most servers and ugly in every email, and the kind of thing that
        // is fixed once rather than remembered at each call site.
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    /** @param path an absolute path beginning with "/" */
    public String url(String path) {
        return baseUrl + path;
    }
}
