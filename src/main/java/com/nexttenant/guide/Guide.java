package com.nexttenant.guide;

import com.nexttenant.seo.Markdown;

import java.time.Instant;
import java.util.UUID;

/**
 * One editorial article at {@code /guides/<slug>}.
 *
 * <p>A record read and written through {@link GuideRepository} rather than a
 * JPA entity, matching how {@code Area} is handled: a guide is written rarely,
 * read constantly, and has no associations to lazy-load. An entity would buy a
 * dirty-checking session this never uses.
 *
 * <p>{@link #body} is Markdown as the author typed it, never HTML. Rendering
 * happens on the way out through {@link Markdown}, so a rendering change is a
 * deploy rather than a data migration — and so that the column can never hold
 * markup somebody could get rendered unescaped.
 *
 * @param publishedAt when it first went live; stays put when the text is later
 *                    corrected, because a publication date that moves with
 *                    every typo tells Google the page is republished rather
 *                    than maintained
 */
public record Guide(
        UUID id,
        String slug,
        String title,
        String summary,
        String body,
        String authorName,
        GuideStatus status,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public boolean isPublished() {
        return status == GuideStatus.PUBLISHED;
    }

    /** "/guides/moving-to-powai" */
    public String path() {
        return "/guides/" + slug;
    }

    /** The body as HTML, safe to emit unescaped. See {@link Markdown}. */
    public String html() {
        return Markdown.toHtml(body);
    }

    /** "Priya" or, when nobody is named, the site itself. */
    public String author() {
        return authorName == null || authorName.isBlank() ? "Next Tenant" : authorName;
    }
}
