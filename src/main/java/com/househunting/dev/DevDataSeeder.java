package com.househunting.dev;

import com.househunting.area.Area;
import com.househunting.area.AreaRepository;
import com.househunting.geo.GeoPoints;
import com.househunting.listing.AgreementHandling;
import com.househunting.listing.Configuration;
import com.househunting.listing.DepositPaidTo;
import com.househunting.listing.FlatmateGender;
import com.househunting.listing.Furnishing;
import com.househunting.listing.HandoverType;
import com.househunting.listing.Listing;
import com.househunting.listing.ListingPrivate;
import com.househunting.listing.ListingPrivateRepository;
import com.househunting.listing.ListingRepository;
import com.househunting.listing.RoomType;
import com.househunting.user.AgeBracket;
import com.househunting.user.Gender;
import com.househunting.user.User;
import com.househunting.user.UserPrivate;
import com.househunting.user.UserPrivateRepository;
import com.househunting.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Populates a local database with realistic Mumbai listings.
 *
 * <p>{@code @Profile("dev")} means the bean is never created in production —
 * not created and disabled, absent.
 *
 * <p>Enough volume to make query plans meaningful. With a dozen rows PostgreSQL
 * ignores every index, because a sequential scan of one page beats any index
 * lookup; plans only start telling the truth at a few hundred rows. Tuning a
 * search against twelve listings teaches you nothing you can rely on.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final int LISTINGS_PER_AREA = 14;
    private static final int LIVE_DAYS = 14;

    /**
     * Fixed seed, deliberately.
     *
     * <p>Random data makes a bug that appears on one run vanish on the next.
     * With a fixed seed the same database comes out every time, so "the Powai
     * 2BHK shows the wrong total" is something two people can look at together.
     */
    private static final long SEED = 42L;

    /** Rough rent level per area, so results sort and filter believably. */
    private static final Map<String, Integer> BASE_RENT = Map.ofEntries(
            Map.entry("Bandra West", 38000), Map.entry("Bandra East", 28000),
            Map.entry("Andheri West", 30000), Map.entry("Andheri East", 26000),
            Map.entry("Powai", 27000), Map.entry("Lower Parel", 42000),
            Map.entry("Malad West", 19000), Map.entry("Goregaon East", 22000),
            Map.entry("Goregaon West", 21000), Map.entry("Chembur", 23000),
            Map.entry("Thane West", 16000), Map.entry("Vile Parle East", 31000),
            Map.entry("Vile Parle West", 33000), Map.entry("Borivali West", 17000),
            Map.entry("Dadar West", 29000), Map.entry("Kurla West", 18000),
            Map.entry("Santacruz West", 35000), Map.entry("Santacruz East", 25000),
            Map.entry("Khar West", 36000), Map.entry("Ghatkopar East", 20000),
            Map.entry("Mulund West", 17500), Map.entry("Worli", 45000));

    private static final String[] NAMES = {
            "Priya", "Rahul", "Aisha", "Vikram", "Neha", "Arjun", "Sneha", "Karan",
            "Meera", "Aditya", "Divya", "Rohit", "Ananya", "Siddharth", "Kavya",
            "Nikhil", "Riya", "Varun", "Pooja", "Tanvi", "Ishaan", "Sanya"};

    private static final List<String> AMENITY_POOL = List.of(
            "LIFT", "PARKING", "WIFI", "WASHING_MACHINE", "FRIDGE", "AC",
            "GEYSER", "POWER_BACKUP", "SECURITY", "GYM");

    private static final List<String> PERK_POOL = List.of(
            "BALCONY", "SEA_VIEW", "PET_FRIENDLY", "TERRACE_ACCESS", "STUDY_DESK");

    private final UserRepository users;
    private final UserPrivateRepository userPrivates;
    private final ListingRepository listings;
    private final ListingPrivateRepository listingPrivates;
    private final AreaRepository areas;

    public DevDataSeeder(UserRepository users,
                         UserPrivateRepository userPrivates,
                         ListingRepository listings,
                         ListingPrivateRepository listingPrivates,
                         AreaRepository areas) {
        this.users = users;
        this.userPrivates = userPrivates;
        this.listings = listings;
        this.listingPrivates = listingPrivates;
        this.areas = areas;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (listings.count() > 0) {
            log.info("Dev seed skipped: {} listings already present", listings.count());
            return;
        }

        List<Area> allAreas = areas.findAllActive();
        Random random = new Random(SEED);
        int phoneCounter = 1;

        log.info("Seeding ~{} listings across {} areas",
                allAreas.size() * LISTINGS_PER_AREA, allAreas.size());

        for (Area area : allAreas) {
            for (int i = 0; i < LISTINGS_PER_AREA; i++) {
                seedOne(area, random, phoneCounter++);
            }
        }

        log.info("Dev seed complete: {} live listings", listings.countByStatus(
                com.househunting.listing.ListingStatus.LIVE));
    }

    private void seedOne(Area area, Random random, int phoneCounter) {
        User leaver = new User(
                NAMES[random.nextInt(NAMES.length)],
                randomOf(random, Gender.MALE, Gender.FEMALE, Gender.OTHER, Gender.UNDISCLOSED),
                randomOf(random, AgeBracket.values()));
        leaver.verifyPhone();
        // One in three has the work-email badge, so the trust signal varies.
        if (random.nextInt(3) == 0) {
            leaver.verifyWorkEmail();
        }
        users.save(leaver);

        // 9000-prefixed numbers are outside the ranges carriers assign, so no
        // real person can receive a message if a dev sender is ever misconfigured.
        userPrivates.save(new UserPrivate(leaver.getId(), "+9190000%05d".formatted(phoneCounter)));

        Configuration configuration = randomOf(random, Configuration.values());
        RoomType roomType = randomOf(random, RoomType.PRIVATE_ROOM, RoomType.PRIVATE_ROOM,
                RoomType.SHARED_ROOM, RoomType.MASTER_ROOM);
        Furnishing furnishing = randomOf(random, Furnishing.values());

        // Rent varies around the area's baseline, then by room type: a shared
        // room is cheaper than a master bedroom in the same flat.
        int base = BASE_RENT.getOrDefault(area.name(), 22000);
        double roomFactor = switch (roomType) {
            case SHARED_ROOM -> 0.62;
            case PRIVATE_ROOM -> 1.0;
            case MASTER_ROOM -> 1.25;
            case ENTIRE_FLAT -> 2.1;
        };
        double furnishingFactor = switch (furnishing) {
            case UNFURNISHED -> 0.88;
            case SEMI_FURNISHED -> 1.0;
            case FULLY_FURNISHED -> 1.15;
        };
        int rent = roundTo500((int) (base * roomFactor * furnishingFactor
                * (0.85 + random.nextDouble() * 0.3)));

        int deposit = rent * (random.nextInt(3) + 1);
        Integer maintenance = random.nextInt(4) == 0 ? null : roundTo500(rent / 12);
        int buyout = random.nextInt(3) == 0 ? roundTo500(random.nextInt(25000)) : 0;

        Listing listing = new Listing(leaver.getId());
        listing.setLocationDetails(area.name(), area.nearestStation(),
                (short) (3 + random.nextInt(18)),
                (short) (1 + random.nextInt(18)),
                random.nextInt(4) > 0);
        listing.setFlatDetails(configuration, roomType,
                randomOf(random, HandoverType.values()),
                (short) random.nextInt(4), furnishing);
        listing.setAmenities(pick(random, AMENITY_POOL, 2 + random.nextInt(5)));
        listing.setPerks(pick(random, PERK_POOL, random.nextInt(3)));
        listing.setMoney(rent, deposit, maintenance, random.nextInt(5) == 0, 0, buyout);
        listing.setHandoverTerms(
                random.nextInt(5) > 0,
                randomOf(random, DepositPaidTo.values()),
                random.nextInt(4) > 0,
                randomOf(random, AgreementHandling.values()),
                (short) (random.nextInt(3) * 30),
                random.nextInt(4) > 0);
        listing.setFlatmateRules(
                (short) (20 + random.nextInt(5)),
                (short) (28 + random.nextInt(10)),
                randomOf(random, FlatmateGender.values()),
                "Working professionals",
                random.nextBoolean(), random.nextInt(4) == 0,
                random.nextInt(5) == 0, random.nextInt(4) > 0, null);
        listing.setAvailableFrom(LocalDate.now().plusDays(random.nextInt(75)));
        listing.advanceWizardTo(6);
        // saveAndFlush: the INSERT must land before listing_private references it.
        listings.saveAndFlush(listing);

        // Scatter pins within the locality rather than stacking them on its
        // centroid, so radius searches and the map behave realistically.
        double spreadDegrees = area.radiusMetres() / 111_000.0;
        double latitude = area.latitude() + (random.nextDouble() - 0.5) * spreadDegrees * 2;
        double longitude = area.longitude() + (random.nextDouble() - 0.5) * spreadDegrees * 2;

        ListingPrivate priv = new ListingPrivate(listing.getId());
        priv.placeAt(GeoPoints.of(latitude, longitude));
        priv.setFullAddress("Flat %d0%d, %s, %s, Mumbai"
                .formatted(1 + random.nextInt(9), 1 + random.nextInt(4),
                        area.name() + " Residency", area.name()));
        priv.setSocietyName(area.name() + " Residency");
        listingPrivates.saveAndFlush(priv);

        // Published at staggered times so "Newest" has a meaningful order.
        listing.publish(Instant.now().minusSeconds(random.nextInt(14 * 86400)), LIVE_DAYS);
        listings.save(listing);
    }

    @SafeVarargs
    private static <T> T randomOf(Random random, T... values) {
        return values[random.nextInt(values.length)];
    }

    private static List<String> pick(Random random, List<String> pool, int count) {
        List<String> copy = new ArrayList<>(pool);
        java.util.Collections.shuffle(copy, random);
        return List.copyOf(copy.subList(0, Math.min(count, copy.size())));
    }

    private static int roundTo500(int amount) {
        return Math.max(500, (amount / 500) * 500);
    }
}
