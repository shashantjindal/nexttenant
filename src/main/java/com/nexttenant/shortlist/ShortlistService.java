package com.nexttenant.shortlist;

import com.nexttenant.listing.dto.ListingCardDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The heart on every card.
 *
 * <p>Deliberately not a JPA entity. {@code shortlists} is a pure join table
 * with a composite primary key, no behaviour, and three operations: insert,
 * delete, count. Mapping it would mean an {@code @IdClass}, a repository and an
 * equals/hashCode contract, all to express {@code INSERT ... ON CONFLICT DO
 * NOTHING}. JPA earns its place on things that have behaviour; this does not.
 */
@Service
public class ShortlistService {

    private final JdbcClient jdbc;

    public ShortlistService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds a listing to someone's shortlist.
     *
     * <p>ON CONFLICT DO NOTHING makes this idempotent, which matters because
     * the heart is a button people double-tap on a phone. Without it the second
     * tap is a constraint violation and a 500 for something entirely harmless.
     *
     * @return true if it was newly added
     */
    @Transactional
    public boolean add(UUID userId, UUID listingId) {
        int inserted = jdbc.sql("""
                        INSERT INTO shortlists (user_id, listing_id)
                        VALUES (?, ?)
                        ON CONFLICT (user_id, listing_id) DO NOTHING
                        """)
                .param(userId)
                .param(listingId)
                .update();

        if (inserted > 0) {
            bumpCount(listingId, 1);
        }
        return inserted > 0;
    }

    @Transactional
    public boolean remove(UUID userId, UUID listingId) {
        int deleted = jdbc.sql("DELETE FROM shortlists WHERE user_id = ? AND listing_id = ?")
                .param(userId)
                .param(listingId)
                .update();

        if (deleted > 0) {
            bumpCount(listingId, -1);
        }
        return deleted > 0;
    }

    /** Returns the new state, so the caller can render the heart. */
    @Transactional
    public boolean toggle(UUID userId, UUID listingId) {
        if (remove(userId, listingId)) {
            return false;
        }
        add(userId, listingId);
        return true;
    }

    /**
     * Which of these listings the user has shortlisted.
     *
     * <p>One query for the whole page rather than one per card. A search page
     * shows twenty cards; asking twenty times is how a page that felt fine in
     * testing becomes slow in use.
     */
    @Transactional(readOnly = true)
    public Set<UUID> shortlistedAmong(UUID userId, List<UUID> listingIds) {
        if (userId == null || listingIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                        SELECT listing_id FROM shortlists
                        WHERE user_id = ? AND listing_id = ANY(?)
                        """)
                .param(userId)
                .param(listingIds.toArray(new UUID[0]))
                .query(UUID.class)
                .list());
    }

    /**
     * The same question, phrased the way a page asks it.
     *
     * <p>Every page that renders cards needs exactly this: "of the cards I am
     * about to show, which are already saved?" Putting the id extraction here
     * rather than in four controllers means four controllers cannot each get
     * it subtly different, and a signed-out viewer (null userId) falls through
     * to the empty set without any of them remembering to check.
     */
    @Transactional(readOnly = true)
    public Set<UUID> savedAmong(UUID userId, List<ListingCardDto> cards) {
        if (userId == null || cards == null || cards.isEmpty()) {
            return Set.of();
        }
        return shortlistedAmong(userId, cards.stream().map(ListingCardDto::id).toList());
    }

    @Transactional(readOnly = true)
    public boolean isShortlisted(UUID userId, UUID listingId) {
        if (userId == null) {
            return false;
        }
        return jdbc.sql("SELECT count(*) FROM shortlists WHERE user_id = ? AND listing_id = ?")
                .param(userId)
                .param(listingId)
                .query(Long.class)
                .single() > 0;
    }

    @Transactional(readOnly = true)
    public long countFor(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM shortlists WHERE user_id = ?")
                .param(userId)
                .query(Long.class)
                .single();
    }

    /**
     * The shortlist page.
     *
     * <p>Only LIVE listings: a shortlisted room that has since been rented or
     * taken down should disappear rather than sit there as a dead link. The
     * shortlist row is kept, so if the owner resumes the listing it returns.
     */
    @Transactional(readOnly = true)
    public List<ListingCardDto> shortlistedListings(UUID userId) {
        return jdbc.sql("""
                        SELECT l.id, l.area_name, l.nearest_station, l.minutes_to_station,
                               l.monthly_rent_inr, l.deposit_inr, l.maintenance_inr,
                               l.maintenance_included, l.brokerage_inr, l.buyout_inr,
                               l.configuration, l.room_type, l.furnishing, l.flatmate_gender,
                               l.available_from, l.published_at,
                               CAST(s.created_at AS text) AS sort_value,
                               -- Same shape as the search query, because both
                               -- feed SearchHit. A shared row mapper is a
                               -- shared contract with every query behind it,
                               -- and this pair has drifted apart once already.
                               (SELECT array_agg(p.id ORDER BY p.position)
                                  FROM (SELECT p.id, p.position FROM photos p
                                         WHERE p.listing_id = l.id
                                           AND p.processed_at IS NOT NULL
                                         ORDER BY p.position LIMIT 5) p) AS photo_ids
                        FROM shortlists s
                        JOIN listings l ON l.id = s.listing_id
                        WHERE s.user_id = ? AND l.status = 'LIVE'
                        ORDER BY s.created_at DESC
                        """)
                .param(userId)
                .query(com.nexttenant.search.SearchHit::fromRow)
                .list()
                .stream()
                .map(com.nexttenant.search.SearchHit::card)
                .toList();
    }

    /** Counter lives in the narrow stats row, not on the wide listings row. */
    private void bumpCount(UUID listingId, int delta) {
        jdbc.sql("""
                        UPDATE listing_stats
                           SET shortlist_count = GREATEST(0, shortlist_count + ?),
                               updated_at = now()
                         WHERE listing_id = ?
                        """)
                .param(delta)
                .param(listingId)
                .update();
    }
}
