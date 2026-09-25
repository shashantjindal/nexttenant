package com.househunting.listing;

import com.househunting.common.RelativeTime;
import com.househunting.common.Rupees;
import com.househunting.geo.GeoPoints;
import com.househunting.listing.dto.ListingDetailDto;
import com.househunting.user.User;
import com.househunting.user.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the listing detail page.
 *
 * <p>Note the constructor: {@link ListingRepository} and {@link UserRepository}
 * only. {@code ListingPrivateRepository} and {@code UserPrivateRepository} are
 * not injected, so this class <em>cannot</em> read an exact location, an
 * address or a phone number — not by policy, but because it holds no reference
 * to anything that could return one.
 *
 * <p>That is the pattern worth copying: the cheapest way to guarantee a class
 * never touches sensitive data is to withhold the tool.
 */
@Service
public class ListingDetailService {

    /**
     * Radius of the published circle.
     *
     * <p>Wider than the 120-300m jitter, so the true building is always
     * somewhere inside the circle shown. A circle narrower than the offset
     * would be a lie in the other direction: it would exclude the real
     * location, which is worse than being vague.
     */
    public static final int PUBLIC_RADIUS_METRES = 500;

    private static final DateTimeFormatter DAY_MONTH =
            DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);

    private final ListingRepository listings;
    private final UserRepository users;
    private final JdbcClient jdbc;
    private final Clock clock;

    public ListingDetailService(ListingRepository listings, UserRepository users,
                                JdbcClient jdbc, Clock clock) {
        this.listings = listings;
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Builds the public view of a DRAFT, for the wizard preview.
     *
     * <p>The design says "This is exactly what someone looking sees", so the
     * preview must be built by the same code that builds the real page. A
     * separate preview renderer would drift, and the drift would be invisible
     * until someone published a listing that looked different from its preview.
     */
    @Transactional(readOnly = true)
    public ListingDetailDto preview(Listing draft) {
        return toDetail(draft);
    }

    @Transactional(readOnly = true)
    public Optional<ListingDetailDto> findPublicListing(UUID id) {
        return listings.findByIdAndStatus(id, ListingStatus.LIVE)
                .map(this::toDetail);
    }

    /**
     * Records a view.
     *
     * <p>A separate write, in its own transaction, against the narrow
     * listing_stats row rather than the wide listings row — Postgres rewrites
     * an entire row on UPDATE, and churning the table every search scans would
     * cost far more than the counter is worth.
     */
    @Transactional
    public void recordView(UUID listingId) {
        jdbc.sql("""
                        UPDATE listing_stats
                           SET view_count = view_count + 1, updated_at = now()
                         WHERE listing_id = ?
                        """)
                .param(listingId)
                .update();
    }

    private ListingDetailDto toDetail(Listing l) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        User leaver = users.findById(l.getLeaverUserId()).orElse(null);

        return new ListingDetailDto(
                l.getId(),

                Rupees.format(l.getMonthlyRentInr()),
                Rupees.format(l.getDepositInr()),
                Rupees.format(l.getMaintenanceInr()),
                l.isMaintenanceIncluded(),
                Rupees.format(l.getBrokerageInr()),
                l.isZeroBrokerage(),
                Rupees.format(l.getBuyoutInr()),
                Rupees.format(l.totalMoveInCostInr()),
                costLines(l),

                l.getAreaName(),
                l.getNearestStation(),
                l.getMinutesToStation() == null ? null : l.getMinutesToStation() + " min walk",
                floorLabel(l.getFloor()),
                l.getHasLift(),
                // The jittered point, never the real one. The map gets these.
                l.getApproxLocation() == null ? 0 : GeoPoints.latitudeOf(l.getApproxLocation()),
                l.getApproxLocation() == null ? 0 : GeoPoints.longitudeOf(l.getApproxLocation()),
                PUBLIC_RADIUS_METRES,

                label(l.getConfiguration()),
                label(l.getRoomType()),
                label(l.getHandoverType()),
                label(l.getFurnishing()),
                l.getExistingFlatmates() == null ? null : (int) l.getExistingFlatmates(),
                prettify(l.getAmenities()),
                prettify(l.getPerks()),

                handoverTerms(l),

                ageRange(l.getFlatmateAgeMin(), l.getFlatmateAgeMax()),
                label(l.getFlatmateGender()),
                l.getFlatmateProfessions(),
                houseRules(l),

                l.getAvailableFrom() == null ? null : l.getAvailableFrom().format(DAY_MONTH),
                RelativeTime.until(l.getAvailableFrom(), today),
                RelativeTime.ago(l.getPublishedAt(), now),

                leaver == null ? null : leaver.getFirstName(),
                leaver == null ? null : RelativeTime.monthYear(leaver.getCreatedAt()),
                leaver != null && leaver.isPhoneVerified(),
                leaver != null && leaver.isWorkEmailVerified(),

                List.of()   // photos arrive with the upload pipeline
        );
    }

    /**
     * The move-in breakdown.
     *
     * <p>"Hidden costs are the biggest reason a takeover falls apart at the
     * door", so every component is listed even when zero — a brokerage line
     * reading ₹0 is the product's central claim, and omitting it would hide the
     * very thing worth showing.
     */
    private List<ListingDetailDto.CostLine> costLines(Listing l) {
        List<ListingDetailDto.CostLine> lines = new ArrayList<>();

        lines.add(new ListingDetailDto.CostLine(
                "First month's rent", Rupees.format(l.getMonthlyRentInr()), false, false));
        lines.add(new ListingDetailDto.CostLine(
                "Security deposit", Rupees.format(l.getDepositInr()), false, false));

        if (l.getMaintenanceInr() != null && l.getMaintenanceInr() > 0) {
            lines.add(new ListingDetailDto.CostLine(
                    l.isMaintenanceIncluded() ? "Society maintenance (in rent)" : "Society maintenance",
                    l.isMaintenanceIncluded() ? "included" : Rupees.format(l.getMaintenanceInr()),
                    false, l.isMaintenanceIncluded()));
        }

        if (l.getBuyoutInr() > 0) {
            lines.add(new ListingDetailDto.CostLine(
                    "Buy-out for furniture", Rupees.format(l.getBuyoutInr()), false, false));
        }

        lines.add(new ListingDetailDto.CostLine(
                "Brokerage",
                l.isZeroBrokerage() ? "₹0" : Rupees.format(l.getBrokerageInr()),
                false, l.isZeroBrokerage()));

        lines.add(new ListingDetailDto.CostLine(
                "Total to move in", Rupees.format(l.totalMoveInCostInr()), true, false));

        return lines;
    }

    /** The six declared facts. */
    private List<ListingDetailDto.Term> handoverTerms(Listing l) {
        List<ListingDetailDto.Term> terms = new ArrayList<>();

        terms.add(term("Owner informed", yesNo(l.getOwnerInformed()),
                "Whether the owner knows this room is changing hands."));
        terms.add(term("Deposit paid to", label(l.getDepositPaidTo()),
                "Who currently holds the deposit, and therefore who returns it."));
        terms.add(term("Registered agreement", yesNo(l.getRegisteredAgreement()),
                "A registered agreement is enforceable; an unregistered one is far weaker."));
        terms.add(term("Agreement handling", label(l.getAgreementHandling()),
                "How the paperwork transfers to you."));
        terms.add(term("Notice period left",
                l.getNoticePeriodDays() == null ? null : l.getNoticePeriodDays() + " days",
                "How long before the current tenant must leave."));
        terms.add(term("Bachelors allowed", yesNo(l.getBachelorsAllowed()),
                "Some societies restrict this regardless of the flatmates."));

        return terms.stream().filter(t -> t.value() != null).toList();
    }

    private List<ListingDetailDto.Term> houseRules(Listing l) {
        List<ListingDetailDto.Term> rules = new ArrayList<>();
        rules.add(term("Non-veg", yesNo(l.getNonVegAllowed()), null));
        rules.add(term("Pets", yesNo(l.getPetsAllowed()), null));
        rules.add(term("Smoking", yesNo(l.getSmokingAllowed()), null));
        rules.add(term("Guests", yesNo(l.getGuestsAllowed()), null));
        return rules.stream().filter(t -> t.value() != null).toList();
    }

    private static ListingDetailDto.Term term(String label, String value, String help) {
        return new ListingDetailDto.Term(label, value, help);
    }

    private static String yesNo(Boolean value) {
        return value == null ? null : value ? "Yes" : "No";
    }

    private static String floorLabel(Short floor) {
        if (floor == null) {
            return null;
        }
        if (floor == 0) {
            return "Ground floor";
        }
        String suffix = switch (floor % 10) {
            case 1 -> floor % 100 == 11 ? "th" : "st";
            case 2 -> floor % 100 == 12 ? "th" : "nd";
            case 3 -> floor % 100 == 13 ? "th" : "rd";
            default -> "th";
        };
        return floor + suffix + " floor";
    }

    private static String ageRange(Short min, Short max) {
        if (min == null || max == null) {
            return null;
        }
        return min + "–" + max;
    }

    /** UPPER_SNAKE codes become "Washing machine" for display. */
    private static List<String> prettify(List<String> codes) {
        return codes.stream()
                .map(code -> {
                    String words = code.replace('_', ' ').toLowerCase(Locale.ENGLISH);
                    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
                })
                .toList();
    }

    /** Reads getLabel() off any of our labelled enums, tolerating null. */
    private static String label(Object enumValue) {
        return switch (enumValue) {
            case null -> null;
            case Configuration c -> c.getLabel();
            case RoomType r -> r.getLabel();
            case HandoverType h -> h.getLabel();
            case Furnishing f -> f.getLabel();
            case DepositPaidTo d -> d.getLabel();
            case AgreementHandling a -> a.getLabel();
            case FlatmateGender g -> g.getLabel();
            default -> enumValue.toString();
        };
    }
}
