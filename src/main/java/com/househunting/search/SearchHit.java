package com.househunting.search;

import com.househunting.listing.Configuration;
import com.househunting.listing.Furnishing;
import com.househunting.listing.RoomType;
import com.househunting.listing.dto.ListingCardDto;
import com.househunting.common.Rupees;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * One row of search results: the card to render, plus what a cursor would need
 * to resume from here.
 *
 * @param sortValue the sort column's value for this row, as text, so the next
 *                  page can seek past it
 */
public record SearchHit(ListingCardDto card, String sortValue, UUID id) {

    private static final DateTimeFormatter DAY_MONTH =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /**
     * Maps a result row.
     *
     * <p>Built from the row rather than by loading the {@code Listing} entity:
     * the search returns twenty cards, and hydrating twenty full entities to
     * read nine columns each is work nobody asked for. More importantly the
     * projection cannot reach private data, because the SELECT does not join
     * {@code listing_private} at all.
     */
    public static SearchHit fromRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);

        Integer rent = getInteger(rs, "monthly_rent_inr");
        Integer deposit = getInteger(rs, "deposit_inr");
        Integer maintenance = getInteger(rs, "maintenance_inr");
        boolean maintenanceIncluded = rs.getBoolean("maintenance_included");
        int brokerage = rs.getInt("brokerage_inr");
        int buyout = rs.getInt("buyout_inr");

        Integer total = totalMoveIn(rent, deposit, maintenance, maintenanceIncluded,
                brokerage, buyout);

        java.sql.Date availableFrom = rs.getDate("available_from");
        Short walkMinutes = getShort(rs, "minutes_to_station");

        ListingCardDto card = new ListingCardDto(
                id,
                Rupees.format(rent),
                Rupees.format(total),
                rs.getString("area_name"),
                labelOf(rs.getString("room_type"), RoomType.class),
                labelOf(rs.getString("configuration"), Configuration.class),
                rs.getString("nearest_station"),
                walkMinutes == null ? null : walkMinutes + " min walk",
                formatAvailability(availableFrom == null ? null : availableFrom.toLocalDate()),
                labelOf(rs.getString("furnishing"), Furnishing.class),
                brokerage == 0,
                null   // cover photo arrives with the photo pipeline
        );

        return new SearchHit(card, rs.getString("sort_value"), id);
    }

    /**
     * Mirrors {@code Listing.totalMoveInCostInr()}.
     *
     * <p>Duplicated deliberately: the entity method operates on an object the
     * search never loads. Both are covered by tests, and a single test asserts
     * the two agree, so the duplication cannot drift silently.
     */
    private static Integer totalMoveIn(Integer rent, Integer deposit, Integer maintenance,
                                       boolean maintenanceIncluded, int brokerage, int buyout) {
        if (rent == null || deposit == null) {
            return null;
        }
        int total = rent + deposit + brokerage + buyout;
        if (maintenance != null && !maintenanceIncluded) {
            total += maintenance;
        }
        return total;
    }

    private static String formatAvailability(LocalDate from) {
        if (from == null) {
            return null;
        }
        return !from.isAfter(LocalDate.now()) ? "Available now" : from.format(DAY_MONTH);
    }

    /** Turns a stored enum name into its display label, tolerating nulls. */
    private static <E extends Enum<E>> String labelOf(String name, Class<E> type) {
        if (name == null) {
            return null;
        }
        E value = Enum.valueOf(type, name);
        return switch (value) {
            case Configuration c -> c.getLabel();
            case RoomType r -> r.getLabel();
            case Furnishing f -> f.getLabel();
            default -> value.name();
        };
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Short getShort(ResultSet rs, String column) throws SQLException {
        short value = rs.getShort(column);
        return rs.wasNull() ? null : value;
    }
}
