package com.nexttenant.seo;

import com.nexttenant.common.SiteProperties;
import com.nexttenant.guide.GuideRepository;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.ListingUrls;
import com.nexttenant.listing.RoomType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The XML sitemap: every page worth indexing, and when it last changed.
 *
 * <p><b>Why this matters more here than on most sites.</b> The listings ARE the
 * search traffic. Nobody is going to outrank NoBroker or Housing.com for "flats
 * for rent mumbai", but "private room in Chembur" is a query with a handful of
 * results and a person with a real intention behind it — and there is one such
 * page per live listing. Without a sitemap, Google finds them only by crawling
 * links from the search page, which paginates, so the deeper ones may never be
 * discovered at all.
 *
 * <p><b>Generated, not a static file.</b> Listings are published and expire
 * daily. A file checked into the repository would be wrong the day after it was
 * written, and being wrong is worse than being absent: a sitemap full of URLs
 * that 404 spends crawl budget on nothing and is read as a quality signal.
 *
 * <p><b>Only pages that are worth fetching.</b> Live listings, published
 * guides, and area and station pages that have at least one room on them. A
 * sitemap is a claim, not an inventory — submitting two hundred empty area
 * pages is submitting a hundred and eighty-nine thin pages for indexing, and
 * they do not become good by being listed here.
 */
@Service
public class SitemapService {

    /**
     * Where one document stops and an index begins.
     *
     * <p>The protocol's own limit is 50,000 URLs and 50MB, and this is a tenth
     * of it — chosen because a smaller document is one Google refetches more
     * cheaply, and because splitting at the protocol limit means the first
     * split happens on the day the site is at its busiest. Crossing a
     * self-imposed line early is uneventful; crossing the real one is not.
     */
    static final int URLS_PER_SITEMAP = 5_000;

    /** Sitemaps use W3C datetime; this is the date-only form, which is allowed. */
    private static final DateTimeFormatter LASTMOD =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;
    private final SiteProperties site;
    private final LandingPageRepository landings;
    private final GuideRepository guides;

    public SitemapService(JdbcClient jdbc, SiteProperties site,
                          LandingPageRepository landings, GuideRepository guides) {
        this.jdbc = jdbc;
        this.site = site;
        this.landings = landings;
        this.guides = guides;
    }

    /** One indexable page. */
    record Entry(String path, Instant lastModified) {
    }

    /**
     * The sections a sitemap index points at.
     *
     * <p>Split by KIND rather than by an arbitrary offset into one big list.
     * Listings churn daily and guides change twice a year, so separate
     * documents let Google refetch the volatile one without re-reading the
     * stable ones — and when something is wrong, "the areas sitemap is empty"
     * is a diagnosis where "chunk 3 is empty" is a puzzle.
     */
    public enum Section {
        PAGES("pages"),
        AREAS("areas"),
        GUIDES("guides"),
        LISTINGS("listings");

        private final String slug;

        Section(String slug) {
            this.slug = slug;
        }

        public String slug() {
            return slug;
        }

        static Section ofSlug(String slug) {
            for (Section section : values()) {
                if (section.slug.equals(slug)) {
                    return section;
                }
            }
            return null;
        }
    }

    /**
     * What {@code /sitemap.xml} serves: one document under the threshold, a
     * sitemap index above it.
     *
     * <p>Switching shape at a threshold rather than always serving an index
     * keeps the common case — a new site with eighty pages — a single file
     * somebody can open and read.
     */
    @Transactional(readOnly = true)
    public String root() {
        List<Entry> all = new ArrayList<>();
        for (Section section : Section.values()) {
            all.addAll(entries(section));
        }

        if (all.size() <= URLS_PER_SITEMAP) {
            return urlSet(all);
        }
        return sitemapIndex();
    }

    /** One section, for {@code /sitemap-listings.xml} and friends. */
    @Transactional(readOnly = true)
    public String section(Section section) {
        return urlSet(entries(section));
    }

    /** How many URLs the sitemap currently carries, across every section. */
    @Transactional(readOnly = true)
    public int size() {
        int total = 0;
        for (Section section : Section.values()) {
            total += entries(section).size();
        }
        return total;
    }

    // ---- Documents ----------------------------------------------------------

    private String urlSet(List<Entry> entries) {
        StringBuilder xml = new StringBuilder(1024);
        // The namespace is exact and is not decoration: a urlset declaring
        // anything else is not a sitemap as far as Google is concerned, and is
        // rejected whole rather than partially read.
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        for (Entry entry : entries) {
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

    private String sitemapIndex() {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        for (Section section : Section.values()) {
            List<Entry> entries = entries(section);
            if (entries.isEmpty()) {
                // An index pointing at an empty document is a fetch that
                // returns nothing. Leaving the section out says the same thing
                // in no bytes.
                continue;
            }
            xml.append("  <sitemap>\n");
            xml.append("    <loc>")
                    .append(escape(site.url("/sitemap-" + section.slug() + ".xml")))
                    .append("</loc>\n");
            newest(entries).ifPresent(instant ->
                    xml.append("    <lastmod>").append(LASTMOD.format(instant)).append("</lastmod>\n"));
            xml.append("  </sitemap>\n");
        }

        return xml.append("</sitemapindex>\n").toString();
    }

    private static java.util.Optional<Instant> newest(List<Entry> entries) {
        return entries.stream()
                .map(Entry::lastModified)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo);
    }

    // ---- Sections -----------------------------------------------------------

    private List<Entry> entries(Section section) {
        return switch (section) {
            case PAGES -> staticPages();
            case AREAS -> areaPages();
            case GUIDES -> guidePages();
            case LISTINGS -> listingPages();
        };
    }

    /**
     * The handful of pages that always exist.
     *
     * <p>No {@code lastmod}: these change when the application is deployed, and
     * claiming today's date on every fetch would tell Google the home page
     * changes daily when it does not. An absent lastmod is read as "unknown",
     * which is the truth.
     */
    private List<Entry> staticPages() {
        return List.of(
                new Entry("/", null),
                new Entry("/flats", null),
                new Entry("/guides", null),
                new Entry("/privacy", null));
    }

    /**
     * Area and station pages that have something on them.
     *
     * <p>The budget variants — {@code /flats-in-powai-under-25000} — are
     * deliberately NOT here. There are nine per area, and whether any given one
     * has rooms on it would take a query each to find out; submitting them all
     * means submitting mostly-empty pages. They are canonical, indexable pages
     * and they are linked from every area page, which is how Google should
     * reach them: because a page linked to it is worth reading, not because we
     * asserted it.
     */
    private List<Entry> areaPages() {
        List<Entry> entries = new ArrayList<>();
        for (LandingPageRepository.SlugWithChange area : landings.areaSlugsWithListings()) {
            entries.add(new Entry("/flats-in-" + area.slug(), area.lastChanged()));
            entries.add(new Entry("/shared-flats-in-" + area.slug(), area.lastChanged()));
        }
        for (LandingPageRepository.SlugWithChange station : landings.stationSlugsWithListings()) {
            entries.add(new Entry("/flats-near-" + station.slug() + "-station",
                    station.lastChanged()));
        }
        return entries;
    }

    private List<Entry> guidePages() {
        return guides.findPublished().stream()
                .map(guide -> new Entry(guide.path(), guide.updatedAt()))
                .toList();
    }

    /**
     * Every live listing.
     *
     * <p>LIVE only. A paused, rented or expired listing returns 404 or 410, so
     * it does not belong in a document whose whole content is a claim that
     * these URLs are worth fetching.
     *
     * <p>A projection rather than loading entities: emitting a URL needs an id
     * and a timestamp, and loading several hundred full listings with their
     * amenities arrays to read two columns would be work for nothing. The three
     * label columns come along because a sitemap URL has to be the CANONICAL
     * one, character for character. Emit the bare id form and every entry
     * redirects: Google reports the submitted URL as "page with redirect" and
     * indexes it under the other address, which is the sitemap working against
     * itself.
     */
    private List<Entry> listingPages() {
        return jdbc.sql("""
                        SELECT id, updated_at, area_name, configuration, room_type
                          FROM listings
                         WHERE status = 'LIVE'
                         ORDER BY updated_at DESC
                         LIMIT :limit
                        """)
                .param("limit", URLS_PER_SITEMAP)
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
                .list();
    }

    /**
     * A stored enum name as its display label, or null if it is neither.
     *
     * <p>The catch is deliberate and is not defensive habit. If a value ever
     * reaches the column that the enum does not know — a migration part
     * applied, a hand-edited row — throwing here fails the whole document, and
     * a sitemap is all-or-nothing: one bad row and every other listing loses
     * its entry. Degrading to a shorter slug costs that one listing a few words
     * in its URL and keeps the rest of the file valid.
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
     * <p>An unescaped {@code &} makes the document malformed, and a malformed
     * sitemap is rejected whole — every URL in it lost, for one ampersand. No
     * URL emitted here carries a query string today; this is what keeps that
     * from mattering the day one does.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
