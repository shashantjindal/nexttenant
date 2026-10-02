package com.nexttenant.seo;

import com.nexttenant.common.SiteProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * The two files search engines look for before anything else.
 *
 * <p>Served from a controller rather than as static files because both depend
 * on the deployment: {@code robots.txt} must name the sitemap by absolute URL,
 * and the sitemap must list what exists right now.
 */
@Controller
public class SeoController {

    private final SitemapService sitemap;
    private final SiteProperties site;

    public SeoController(SitemapService sitemap, SiteProperties site) {
        this.sitemap = sitemap;
        this.site = site;
    }

    /**
     * What a crawler may fetch.
     *
     * <p><b>This is not a security control</b>, and treating it as one is a
     * classic mistake: robots.txt is a public file that politely asks
     * well-behaved crawlers to stay out, and it is the first place anybody
     * looks to find the paths you would rather they did not. The private areas
     * below are protected by {@code SecurityConfig} — which returns a redirect
     * to a stranger whether they read this file or not. Listing them here only
     * stops Google wasting its time fetching pages it will never be allowed to
     * see.
     *
     * <p><b>Disallow is not noindex.</b> A disallowed page can still be indexed
     * from links elsewhere, titleless and described as "no information is
     * available" — because the crawler is forbidden from fetching it and so
     * never sees the noindex tag that would have settled the matter. The two
     * are therefore used for different things here: {@code Disallow} for URL
     * shapes that are infinite or pointless to fetch, and the {@code noindex}
     * meta tag, which requires the page to be fetchable, for pages that exist
     * and should not be listed.
     */
    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String robots() {
        return """
                User-agent: *

                # The pages worth indexing: the landing page, the clean area and
                # station pages, one page per live listing, and the guides.
                Allow: /$
                Allow: /flats
                Allow: /flats-in-
                Allow: /shared-flats-in-
                Allow: /flats-near-
                Allow: /guides
                Allow: /photos/

                # Signed-in areas. Protected by the application; listed here so
                # crawlers do not spend their budget being redirected.
                Disallow: /login
                Disallow: /logout
                Disallow: /dashboard
                Disallow: /requests
                Disallow: /shortlist
                Disallow: /list
                Disallow: /admin/
                Disallow: /actuator/
                Disallow: /error

                # Actions on a listing, not pages about one.
                Disallow: /flats/*/request
                Disallow: /flats/*/report

                # The filter UI. Every one of these produces a URL for a
                # combination of filters rather than for a thing, so the space is
                # unbounded over a bounded set of listings — a crawler left to it
                # spends its whole visit on permutations instead of on the rooms.
                #
                # The content behind them is not lost: each filtered view names
                # the clean area page as its canonical, and those are allowed
                # above. This is the one case where Disallow is right, because
                # the point is to stop the FETCH, not to stop the listing.
                Disallow: /flats?
                Disallow: /*?*cursor=
                Disallow: /*?*sort=
                Disallow: /*?*rentMin=
                Disallow: /*?*rentMax=
                Disallow: /*?*amenity=
                Disallow: /*?*config=
                Disallow: /*?*room=
                Disallow: /*?*furnishing=

                # The autocomplete endpoint. JSON, not a page.
                Disallow: /flats/areas

                Sitemap: %s
                """.formatted(site.url("/sitemap.xml"));
    }

    /**
     * Every page worth indexing — or, once there are enough of them, an index
     * of the documents that list them.
     *
     * <p>Regenerated per request. That is a handful of indexed queries and
     * Google fetches this at most daily, so caching it would be machinery
     * guarding a cost nobody is paying.
     */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemap() {
        return sitemap.root();
    }

    /**
     * One section of a split sitemap.
     *
     * <p>These stay reachable below the split threshold too, when
     * {@code /sitemap.xml} is a single document that nothing points at them
     * from. That costs nothing and means a URL Google has already fetched does
     * not start 404ing on the day the site shrinks back under the threshold —
     * which is the kind of flapping that gets a sitemap distrusted.
     */
    @GetMapping(value = "/sitemap-{section}.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemapSection(@PathVariable String section) {
        SitemapService.Section parsed = SitemapService.Section.ofSlug(section);
        if (parsed == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return sitemap.section(parsed);
    }
}
