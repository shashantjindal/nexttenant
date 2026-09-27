package com.househunting.seo;

import com.househunting.common.SiteProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The two files search engines look for before anything else.
 *
 * <p>Both were already permitted in {@code SecurityConfig} and neither
 * existed, so both returned 404 — which is not fatal, but means Google
 * discovers listing pages only by crawling links from a paginated search
 * page, and may never reach the deeper ones at all.
 *
 * <p>Served from a controller rather than as static files because both depend
 * on the deployment: {@code robots.txt} must name the sitemap by absolute URL,
 * and the sitemap must list the listings that exist right now.
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
     */
    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String robots() {
        return """
                User-agent: *

                # Everything worth indexing: the landing page, the search page
                # and one page per live listing.
                Allow: /$
                Allow: /flats
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

                # Paging and sorting produce an unbounded number of URLs for a
                # bounded number of listings. Left crawlable, a search page
                # with filters is an infinite space, and a crawler spends its
                # whole visit on permutations instead of on the listings
                # themselves. Every one of these is reachable another way.
                Disallow: /*?*cursor=
                Disallow: /*?*sort=

                Sitemap: %s
                """.formatted(site.url("/sitemap.xml"));
    }

    /**
     * Every page worth indexing, with the date it last changed.
     *
     * <p>Regenerated per request. That is one indexed query over a few hundred
     * rows, and Google fetches this at most daily — caching it would be
     * machinery guarding a cost nobody is paying. See {@link SitemapService}
     * for the point at which that stops being true.
     */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemap() {
        return sitemap.xml();
    }
}
