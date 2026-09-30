package com.nexttenant.seo;

import com.nexttenant.common.SiteProperties;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.ListingUrls;
import com.nexttenant.listing.RoomType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * The XML sitemap: a list of every page worth indexing.
 *
 * <p><b>Why this matters more here than on most sites.</b> The listings ARE
 * the search traffic. Nobody is going to outrank NoBroker or Housing.com for
 * "flats for rent mumbai", but "private room in Chembur" is a query with a
 * handful of results and a person with a real intention behind it — and there
 * is one such page per live listing. Without a sitemap, Google finds them only
 * by crawling links from the search page, which paginates, so the deeper ones
 * may never be discovered at all.
 *
 * <p><b>Generated, not a static file.</b> Listings are published and expire
 * daily. A file checked into the repository would be wrong the day after it
 * was written, and being wrong is worse than being absent: a sitemap full of
 * URLs that 404 spends crawl budget on nothing and is read as a quality
 * signal.
 */
@Service
public class SitemapService {

    /**
     * The sitemap protocol's limit: 50,000 URLs or 50MB uncompressed.
     *
     * <p>Not enforced by splitting, because at a few hundred listings that
     * would be machinery for a problem nobody has. It IS checked, so that the
     * day it matters somebody is told rather than discovering that Google
     * silently stopped reading past the limit.
     */
    private static final int MAX_URLS = 50_000;

    /** Sitemaps use W3C datetime; this is the date-only form, which is allowed. */
    private static final DateTimeFormatter LASTMOD =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;
    private final SiteProperties site;

    public SitemapService(JdbcClient jdbc, SiteProperties site) {
        this.jdbc = jdbc;
        this.site = site;
    }

    /** One indexable page. */
    private record Entry(String path, Instant lastModified) {
    }

    @Transactional(readOnly = true)
    public String xml() {
        StringBuilder xml = new StringBuilder(1024);
        // The namespace is exact and is not decoration: a urlset declaring
        // anything else is not a sitemap as far as Google is concerned, and is
        // rejected whole rather than partially read.
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        for (Entry entry : entries()) {
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escape(site.url(entry.path()))).append("</loc>\n");
            if (entry.lastModified() != null) {
                xml.append("    <lastmod>")
                        .append(LASTMOD.format(entry.lastModified()))
                        .append("</lastmod>\n");
            }
            // No <changefreq> and no <priority>. Google has said publicly for
            // years that it ignores both, and they are the two tags every
            // sitemap tutorial spends its time on. Bytes that do nothing.
            xml.append("  </url>\n");
        }

        return xml.append("</urlset>\n").toString();
    }

    /** How many URLs the sitemap currently carries. */
    @Transactional(readOnly = true)
    public int size() {
        return entries().size();
    }

    /** True once the sitemap is close enough to the protocol limit to act. */
    public boolean nearingLimit() {
        return size() > MAX_URLS * 9 / 10;
    }

    private List<Entry> entries() {
        List<Entry> entries = new java.util.ArrayList<>();

        // The two fixed pages. The landing page first, by convention — the
        // order carries no weight, but a sitemap that opens with the home page
        // is easier for a person to read.
        entries.add(new Entry("/", null));
        entries.add(new Entry("/flats", null));

        // LIVE only. A sitemap is a claim that these URLs are worth fetching,
        // so a paused or expired listing — which returns 404 — does not belong
        // in it.
        //
        // A projection rather than loading entities: emitting a URL needs an id
        // and a timestamp, and loading several hundred full listings with their
        // amenities arrays to read two columns would be work for nothing.
        // The three label columns come along because a sitemap URL has to be
        // the CANONICAL one, character for character. Emit the bare id form
        // and every entry redirects: Google reports the submitted URL as "page
        // with redirect" and indexes it under the other address, which is the
        // sitemap working against itself.
        jdbc.sql("""
                        SELECT id, updated_at, area_name, configuration, room_type
                          FROM listings
                         WHERE status = 'LIVE'
                         ORDER BY updated_at DESC
                         LIMIT :limit
                        """)
                .param("limit", MAX_URLS)
                .query((rs, n) -> new Entry(
                        ListingUrls.path(
                                rs.getObject("id", UUID.class),
                                rs.getString("area_name"),
                                // Stored as enum names ('BHK_2'); the slug is
                                // built from the labels ('2 BHK') so that it
                                // matches what ListingDetailDto produces.
                                configurationLabel(rs.getString("configuration")),
                                roomTypeLabel(rs.getString("room_type"))),
                        rs.getTimestamp("updated_at").toInstant()))
                .list()
                .forEach(entries::add);

        return entries;
    }

    /**
     * A stored enum name as its display label, or null if it is neither.
     *
     * <p>The catch is deliberate and is not defensive habit. If a value ever
     * reaches the column that the enum does not know — a migration part
     * applied, a hand-edited row — throwing here fails the whole document, and
     * a sitemap is all-or-nothing: one bad row and every other listing loses
     * its entry. Degrading to a shorter slug costs that one listing a few
     * words in its URL and keeps the rest of the file valid.
     */
    private static String configurationLabel(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Configuration.valueOf(name).getLabel();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String roomTypeLabel(String name) {
        if (name == null) {
            return null;
        }
        try {
            return RoomType.valueOf(name).getLabel();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * XML-escapes a URL.
     *
     * <p>Listing URLs are {@code /flats/<uuid>} and contain nothing that needs
     * escaping today. This exists for the day area pages are added: those
     * carry query strings, an unescaped {@code &} makes the document
     * malformed, and a malformed sitemap is rejected whole — every URL in it
     * lost, for one ampersand.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
