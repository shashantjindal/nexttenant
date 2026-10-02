package com.nexttenant.guide;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads and writes the {@code guides} table. */
@Repository
public class GuideRepository {

    private static final String SELECT = """
            SELECT id, slug, title, summary, body, author_name,
                   status, published_at, created_at, updated_at
              FROM guides
            """;

    private final JdbcClient jdbc;

    public GuideRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A published guide, by slug.
     *
     * <p>The status filter is in the query rather than checked by the caller.
     * A draft is an unfinished thought with the author's name on it, and the
     * difference between "not published" and "published" must not depend on
     * every call site remembering an if.
     */
    @Transactional(readOnly = true)
    public Optional<Guide> findPublished(String slug) {
        return jdbc.sql(SELECT + " WHERE slug = :slug AND status = 'PUBLISHED'")
                .param("slug", slug)
                .query(Guide.class)
                .optional();
    }

    /** Everything live, newest first — the index page and the sitemap. */
    @Transactional(readOnly = true)
    public List<Guide> findPublished() {
        return jdbc.sql(SELECT + " WHERE status = 'PUBLISHED' ORDER BY published_at DESC")
                .query(Guide.class)
                .list();
    }

    /** Drafts included: the admin list. */
    @Transactional(readOnly = true)
    public List<Guide> findAll() {
        return jdbc.sql(SELECT + " ORDER BY coalesce(published_at, created_at) DESC")
                .query(Guide.class)
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<Guide> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(Guide.class)
                .optional();
    }

    @Transactional(readOnly = true)
    public boolean slugTaken(String slug, UUID exceptId) {
        return jdbc.sql("""
                        SELECT count(*) FROM guides
                         WHERE slug = :slug AND (:id::uuid IS NULL OR id <> :id::uuid)
                        """)
                .param("slug", slug)
                .param("id", exceptId)
                .query(Long.class)
                .single() > 0;
    }

    @Transactional
    public UUID create(String slug, String title, String summary, String body,
                       String authorName, GuideStatus status) {
        return jdbc.sql("""
                        INSERT INTO guides (slug, title, summary, body, author_name,
                                            status, published_at)
                        VALUES (:slug, :title, :summary, :body, :author,
                                :status,
                                -- Set at the moment of publication and only
                                -- then, which is what guides_published_has_date_ck
                                -- requires and what makes datePublished in the
                                -- structured data mean something.
                                --
                                -- now() rather than a timestamp bound from Java:
                                -- the driver cannot infer a SQL type for an
                                -- Instant, and the database clock is the one
                                -- every other timestamp in this table came from.
                                CASE WHEN :status = 'PUBLISHED' THEN now() END)
                        RETURNING id
                        """)
                .param("slug", slug)
                .param("title", title)
                .param("summary", summary)
                .param("body", body)
                .param("author", authorName)
                .param("status", status.name())
                .query(UUID.class)
                .single();
    }

    /**
     * Saves an edit.
     *
     * <p>{@code published_at} uses COALESCE so that it is set the first time a
     * guide goes live and never moved afterwards — including when a published
     * guide is pulled back to draft and published again. The alternative,
     * overwriting it on every publish, makes a two-year-old guide look new
     * every time somebody fixes a typo in it, and Google notices.
     */
    @Transactional
    public void update(UUID id, String slug, String title, String summary, String body,
                       String authorName, GuideStatus status) {
        jdbc.sql("""
                        UPDATE guides
                           SET slug = :slug,
                               title = :title,
                               summary = :summary,
                               body = :body,
                               author_name = :author,
                               status = :status,
                               published_at = CASE
                                   WHEN :status = 'PUBLISHED'
                                   THEN coalesce(published_at, now())
                                   ELSE published_at
                               END,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("title", title)
                .param("summary", summary)
                .param("body", body)
                .param("author", authorName)
                .param("status", status.name())
                .update();
    }

    @Transactional
    public void delete(UUID id) {
        jdbc.sql("DELETE FROM guides WHERE id = :id").param("id", id).update();
    }
}
