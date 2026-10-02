package com.nexttenant.seo;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.nexttenant.common.SiteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code application/ld+json} block that tells Google what a page
 * is about.
 *
 * <h2>Why a serialiser and not string concatenation</h2>
 *
 * <p>The output is written into the page with {@code th:utext} — unescaped,
 * because escaped JSON is not JSON. That makes this class the one place in the
 * application where user-supplied text reaches the HTML without Thymeleaf's
 * escaping, and an apostrophe in an area description would be a broken page
 * while a {@code </script>} in one would be a cross-site scripting hole.
 *
 * <p>So the JSON is built as maps and serialised by Jackson, which escapes
 * quotes and control characters correctly, and then {@code </} is escaped on
 * the way out — see {@link #serialise}. Neither step is optional and neither is
 * obvious from reading the template, which is why both live here.
 *
 * <h2>One graph, not five script tags</h2>
 *
 * <p>Every page emits a single block containing an {@code @graph} array: the
 * Organization, the BreadcrumbList, and whatever the page itself is. Several
 * separate blocks are legal and Google reads them, but they cannot reference
 * each other by {@code @id} — so the publisher of an article ends up repeated
 * in full rather than pointing at the Organization already on the page.
 */
@Component
public class JsonLd {

    private static final Logger log = LoggerFactory.getLogger(JsonLd.class);

    private static final String SCHEMA = "https://schema.org";

    /** Everything money-shaped on this site is in rupees. */
    private static final String CURRENCY = "INR";

    private final ObjectMapper json;
    private final SiteProperties site;

    public JsonLd(ObjectMapper json, SiteProperties site) {
        this.json = json;
        this.site = site;
    }

    /** One step in the trail from the home page to here. */
    public record Crumb(String name, String path) {
    }

    /**
     * A listing, for the detail page.
     *
     * <p><b>On the type.</b> Schema.org's {@code Accommodation} is the accurate
     * description of a room, and {@code offers} is not one of its properties —
     * it belongs to {@code Product}. Multi-typing the node as both is valid
     * JSON-LD and is what lets the price be expressed at all; the alternatives
     * are a {@code Product} that lies about what it is, or an
     * {@code Accommodation} with no price, which is the one fact a searcher
     * wants.
     *
     * <p><b>What is deliberately absent.</b> No street address, no society
     * name, no exact coordinate. The whole product promises approximate
     * locations until two people have agreed to talk, and structured data is
     * not an exemption from that — it is the most machine-readable place we
     * could possibly break the promise. {@code addressLocality} is the area
     * name, which is what the page shows a human.
     *
     * @param rent     monthly rent in rupees, or null for a listing that has
     *                 not got that far
     * @param photoUrl site-relative path to the cover photo, or null
     */
    public String listing(String name,
                          String description,
                          String canonicalPath,
                          Integer rent,
                          Integer deposit,
                          String area,
                          Integer numberOfRooms,
                          List<String> amenities,
                          LocalDate availableFrom,
                          String photoUrl,
                          List<Crumb> crumbs) {

        Map<String, Object> accommodation = node("Accommodation");
        // Multi-typed so that `offers` below is a property this node actually
        // has. See the class comment.
        accommodation.put("@type", List.of("Accommodation", "Product"));
        accommodation.put("@id", site.url(canonicalPath) + "#listing");
        accommodation.put("name", name);
        putIfPresent(accommodation, "description", description);
        accommodation.put("url", site.url(canonicalPath));

        if (photoUrl != null) {
            accommodation.put("image", site.url(photoUrl));
        }
        if (numberOfRooms != null) {
            accommodation.put("numberOfRooms", numberOfRooms);
        }
        if (amenities != null && !amenities.isEmpty()) {
            List<Object> features = new ArrayList<>();
            for (String amenity : amenities) {
                Map<String, Object> feature = new LinkedHashMap<>();
                feature.put("@type", "LocationFeatureSpecification");
                feature.put("name", amenity);
                // value=true rather than omitted: the type models a feature
                // that may be present or absent, and an unqualified one reads
                // as "mentioned", not "has".
                feature.put("value", true);
                features.add(feature);
            }
            accommodation.put("amenityFeature", features);
        }

        Map<String, Object> address = new LinkedHashMap<>();
        address.put("@type", "PostalAddress");
        putIfPresent(address, "addressLocality", area);
        address.put("addressRegion", "Maharashtra");
        address.put("addressCountry", "IN");
        accommodation.put("address", address);

        if (rent != null) {
            Map<String, Object> offer = new LinkedHashMap<>();
            offer.put("@type", "Offer");
            offer.put("price", rent);
            offer.put("priceCurrency", CURRENCY);
            // InStock means "this room can be taken"; a listing that is not
            // LIVE never reaches this method, because a page that is noindexed
            // has no business claiming availability either.
            offer.put("availability", SCHEMA + "/InStock");
            offer.put("url", site.url(canonicalPath));
            if (availableFrom != null) {
                offer.put("availabilityStarts", availableFrom.toString());
            }

            // The rent is per month, and a bare `price` on a rental reads as a
            // sale price — the difference between ₹28,000 a month and a
            // ₹28,000 flat. unitText spells it out.
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("@type", "UnitPriceSpecification");
            spec.put("price", rent);
            spec.put("priceCurrency", CURRENCY);
            spec.put("unitCode", "MON");
            spec.put("unitText", "month");
            offer.put("priceSpecification", spec);

            accommodation.put("offers", offer);
        }
        if (deposit != null) {
            accommodation.put("additionalProperty", List.of(propertyValue("Deposit", deposit)));
        }

        return graph(accommodation, crumbs);
    }

    /**
     * The listings shown on an area or station page.
     *
     * <p>An {@code ItemList} of URLs rather than of full listing objects.
     * Repeating every listing's price and amenities on the area page would
     * duplicate, on thirty pages, data that already exists on each listing's
     * own page — and duplicated structured data is how the two versions start
     * disagreeing. The list says "these are the items and this is their order";
     * each URL says the rest.
     */
    public String itemList(String name, String canonicalPath,
                           List<String> itemPaths, List<Crumb> crumbs) {
        Map<String, Object> list = node("ItemList");
        list.put("@id", site.url(canonicalPath) + "#listings");
        list.put("name", name);
        list.put("numberOfItems", itemPaths.size());
        // Rank matters here and is not decorative: the page is sorted, and a
        // list that does not say so invites a crawler to treat the order as
        // arbitrary.
        list.put("itemListOrder", SCHEMA + "/ItemListOrderAscending");

        List<Object> elements = new ArrayList<>(itemPaths.size());
        for (int i = 0; i < itemPaths.size(); i++) {
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("@type", "ListItem");
            element.put("position", i + 1);
            element.put("url", site.url(itemPaths.get(i)));
            elements.add(element);
        }
        list.put("itemListElement", elements);

        return graph(list, crumbs);
    }

    /** A guide. */
    public String article(String headline, String description, String canonicalPath,
                          String author, java.time.Instant published, java.time.Instant updated,
                          String imagePath, List<Crumb> crumbs) {
        Map<String, Object> article = node("Article");
        article.put("@id", site.url(canonicalPath) + "#article");
        article.put("headline", headline);
        putIfPresent(article, "description", description);
        article.put("url", site.url(canonicalPath));

        if (imagePath != null) {
            article.put("image", site.url(imagePath));
        }
        if (published != null) {
            article.put("datePublished", published.toString());
        }
        // Google treats a missing dateModified as "never updated since
        // publication", which for an evergreen guide is a slow decay nobody
        // notices. Falling back to the publication date is accurate rather
        // than flattering.
        article.put("dateModified", (updated != null ? updated : published) != null
                ? (updated != null ? updated : published).toString()
                : null);
        article.values().removeIf(java.util.Objects::isNull);

        Map<String, Object> by = new LinkedHashMap<>();
        by.put("@type", "Person");
        by.put("name", author != null ? author : "Next Tenant");
        article.put("author", by);

        // A reference, not a copy: the Organization is already in the graph.
        article.put("publisher", Map.of("@id", site.url("/") + "#organization"));

        return graph(article, crumbs);
    }

    /** For pages that are only themselves — the home page, the privacy policy. */
    public String page(List<Crumb> crumbs) {
        return graph(null, crumbs);
    }

    // ---- The graph ---------------------------------------------------------

    private String graph(Map<String, Object> primary, List<Crumb> crumbs) {
        List<Object> nodes = new ArrayList<>(3);
        nodes.add(organization());
        if (crumbs != null && !crumbs.isEmpty()) {
            nodes.add(breadcrumbs(crumbs));
        }
        if (primary != null) {
            nodes.add(primary);
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("@context", SCHEMA);
        document.put("@graph", nodes);
        return serialise(document);
    }

    /**
     * Who publishes this site.
     *
     * <p>On every page, with a stable {@code @id}, so that everything else can
     * point at it instead of restating it. This is also the node that gives
     * Google something to attach a knowledge panel to, which for a new site is
     * the only route to a branded result that is not an advertisement.
     */
    private Map<String, Object> organization() {
        Map<String, Object> org = node("Organization");
        org.put("@id", site.url("/") + "#organization");
        org.put("name", "Next Tenant");
        org.put("url", site.url("/"));
        org.put("logo", site.url("/img/logo.png"));
        org.put("email", "hello@nexttenant.in");
        org.put("areaServed", Map.of("@type", "City", "name", "Mumbai"));
        org.put("description",
                "Shared flats in Mumbai, taken over directly from the person leaving. "
                        + "No brokers, no owners, no PGs.");
        return org;
    }

    private Map<String, Object> breadcrumbs(List<Crumb> crumbs) {
        Map<String, Object> list = node("BreadcrumbList");
        List<Object> items = new ArrayList<>(crumbs.size());
        for (int i = 0; i < crumbs.size(); i++) {
            Crumb crumb = crumbs.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("@type", "ListItem");
            item.put("position", i + 1);
            item.put("name", crumb.name());
            // `item` is the URL. The last crumb is the current page and may
            // legally omit it; it is included anyway, because Google's own
            // examples do and omitting it is the single most common way a
            // breadcrumb fails validation.
            item.put("item", site.url(crumb.path()));
            items.add(item);
        }
        list.put("itemListElement", items);
        return list;
    }

    private static Map<String, Object> node(String type) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("@type", type);
        return map;
    }

    private static Map<String, Object> propertyValue(String name, Object value) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("@type", "PropertyValue");
        property.put("name", name);
        property.put("value", value);
        property.put("unitText", CURRENCY);
        return property;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /**
     * Maps to JSON, safe to drop into a {@code <script>} element unescaped.
     *
     * <p>Jackson handles quotes, backslashes and control characters. What it
     * does NOT do is escape a forward slash, so a description containing
     * {@code </script>} would close the block early and everything after it
     * would be parsed as HTML. {@code <\/} is a valid JSON escape that no
     * parser treats differently and that no HTML parser reads as a closing
     * tag — one replacement, and the hole is shut.
     *
     * <p>Returning null on failure rather than throwing: structured data is an
     * enhancement, and a page that fails to render because its JSON-LD could
     * not be built has traded something that matters for something that does
     * not. The log line is there so it is still a bug somebody can find.
     */
    private String serialise(Object document) {
        try {
            return json.writeValueAsString(document).replace("</", "<\\/");
        } catch (JacksonException e) {
            log.warn("Could not build JSON-LD; page will render without it", e);
            return null;
        }
    }
}
