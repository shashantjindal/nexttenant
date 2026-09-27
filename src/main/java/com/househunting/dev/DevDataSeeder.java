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
import java.util.Locale;
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

    /**
     * Was 14, when there were 22 areas. V10 took the area list to 98, and 14
     * apiece would be 1,372 listings — several minutes of seeding, most of it
     * spent generating photos nobody will scroll to. Four apiece keeps the
     * total near 400, which is still well past the few-hundred-row mark where
     * PostgreSQL starts choosing index scans and query plans begin to mean
     * something.
     */
    private static final int LISTINGS_PER_AREA = 4;

    /**
     * How many photos each seeded listing gets. MIN_PHOTOS_TO_PUBLISH is 3.
     *
     * <p>Measured before choosing: photos dominate the seed. Each one is drawn,
     * encoded, decoded by ImageProcessor, resized, re-encoded and thumbnailed —
     * about 110ms. At 3–5 apiece across 392 listings that was 1,596 images and
     * just over three minutes of staring at a log.
     *
     * <p>3–4 is the useful range anyway: three is the publishing minimum, so it
     * has to appear, and four exercises the gallery's paging. A fifth photo
     * tests nothing a fourth did not.
     */
    private static final int MIN_SEED_PHOTOS = 3;
    private static final int MAX_SEED_PHOTOS = 4;
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
            Map.entry("Mulund West", 17500), Map.entry("Worli", 45000),

            // ---- Added with V10's areas ------------------------------------
            // Rough market levels for a private room. The absolute numbers
            // matter less than the ORDER: a search sorted by rent should put
            // Kalyan above Malabar Hill, and a filter of "under 20,000" should
            // return the far suburbs rather than an arbitrary slice.
            Map.entry("Colaba", 42000), Map.entry("Cuffe Parade", 45000),
            Map.entry("Nariman Point", 45000), Map.entry("Fort", 35000),
            Map.entry("Churchgate", 40000), Map.entry("Marine Lines", 35000),
            Map.entry("Girgaon", 26000), Map.entry("Malabar Hill", 50000),
            Map.entry("Walkeshwar", 48000), Map.entry("Napean Sea Road", 50000),
            Map.entry("Breach Candy", 50000), Map.entry("Peddar Road", 48000),
            Map.entry("Tardeo", 38000), Map.entry("Grant Road", 30000),
            Map.entry("Mumbai Central", 28000), Map.entry("Byculla", 24000),
            Map.entry("Mazgaon", 24000), Map.entry("Sewri", 26000),
            Map.entry("Mahalaxmi", 40000), Map.entry("Parel", 34000),
            Map.entry("Prabhadevi", 38000), Map.entry("Dadar East", 27000),
            Map.entry("Matunga East", 26000), Map.entry("Matunga West", 27000),
            Map.entry("Mahim", 27000), Map.entry("Wadala", 26000),
            Map.entry("Antop Hill", 22000), Map.entry("Sion", 25000),

            Map.entry("Bandra Kurla Complex", 45000), Map.entry("Khar East", 28000),
            Map.entry("Kalina", 26000), Map.entry("Vakola", 24000),
            Map.entry("Juhu", 42000), Map.entry("Versova", 32000),
            Map.entry("Lokhandwala", 33000), Map.entry("Oshiwara", 28000),
            Map.entry("Four Bungalows", 30000), Map.entry("Seven Bungalows", 31000),
            Map.entry("Marol", 24000), Map.entry("Sakinaka", 20000),
            Map.entry("Chandivali", 24000), Map.entry("Jogeshwari West", 22000),
            Map.entry("Jogeshwari East", 21000), Map.entry("Malad East", 20000),
            Map.entry("Kandivali West", 19000), Map.entry("Kandivali East", 18000),
            Map.entry("Borivali East", 17000), Map.entry("Dahisar West", 15000),
            Map.entry("Dahisar East", 14500), Map.entry("Mira Road", 13000),
            Map.entry("Bhayandar", 12000),

            Map.entry("Kurla East", 17000), Map.entry("Ghatkopar West", 21000),
            Map.entry("Vidyavihar", 19000), Map.entry("Vikhroli West", 20000),
            Map.entry("Vikhroli East", 19000), Map.entry("Kanjurmarg", 20000),
            Map.entry("Bhandup West", 17000), Map.entry("Bhandup East", 16000),
            Map.entry("Mulund East", 17000), Map.entry("Thane East", 15000),
            Map.entry("Ghodbunder Road", 15000), Map.entry("Dombivli", 10000),
            Map.entry("Kalyan", 9000),

            Map.entry("Govandi", 14000), Map.entry("Mankhurd", 13000),
            Map.entry("Vashi", 22000), Map.entry("Sanpada", 20000),
            Map.entry("Nerul", 19000), Map.entry("Seawoods", 21000),
            Map.entry("CBD Belapur", 17000), Map.entry("Kharghar", 14000),
            Map.entry("Airoli", 18000), Map.entry("Ghansoli", 15000),
            Map.entry("Kopar Khairane", 16000), Map.entry("Panvel", 11000));

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
    private final com.househunting.photo.PhotoService photos;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public DevDataSeeder(UserRepository users,
                         UserPrivateRepository userPrivates,
                         ListingRepository listings,
                         ListingPrivateRepository listingPrivates,
                         AreaRepository areas,
                         com.househunting.photo.PhotoService photos,
                         org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.users = users;
        this.userPrivates = userPrivates;
        this.listings = listings;
        this.listingPrivates = listingPrivates;
        this.areas = areas;
        this.photos = photos;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (listings.count() > 0) {
            log.info("Dev seed skipped: {} listings already present", listings.count());
            // Analysed on the skip path too, not only after seeding. A database
            // filled by an older build has no statistics for these tables, and
            // the symptom is not an error — it is the planner quietly choosing
            // worse plans than production would. Milliseconds on a few hundred
            // rows, and it makes "the plan I see locally" mean something.
            analyse();
            return;
        }

        // Grouped rather than flat, so some listings can land in a sublocality.
        //
        // Looping over every area including sublocalities would put four
        // listings in Diamond Garden and four in Chembur Colony — an even
        // spread that is not how a neighbourhood looks, and that would make
        // Chembur itself look empty. Looping over localities only would leave
        // every sublocality unused, so nothing would demonstrate that ticking
        // "Chembur" also finds a room in Diamond Garden.
        //
        // So: the quota stays per locality, and each listing in it picks either
        // the locality or one of its sublocalities.
        List<AreaRepository.AreaGroup> groups = areas.findGrouped();
        Random random = new Random(SEED);
        int phoneCounter = 1;

        int total = groups.size() * LISTINGS_PER_AREA;
        long startedAt = System.currentTimeMillis();

        // Says how long this will take, because it takes a while — generating
        // and processing four photographs per listing is most of a couple of
        // minutes. A progress line every 50 is the difference between "it is
        // working" and "it has hung", and somebody watching a silent console
        // for two minutes will assume the second.
        log.info("Seeding {} listings across {} areas, with photos. "
                        + "This takes a minute or two on a fresh database.",
                total, groups.size());

        int done = 0;
        for (AreaRepository.AreaGroup group : groups) {
            for (int i = 0; i < LISTINGS_PER_AREA; i++) {
                seedOne(placeWithin(group, random), random, phoneCounter++);
                if (++done % 50 == 0) {
                    log.info("  seeded {}/{} listings", done, total);
                }
            }
        }

        analyse();

        log.info("Dev seed complete: {} live listings in {}s",
                listings.countByStatus(com.househunting.listing.ListingStatus.LIVE),
                (System.currentTimeMillis() - startedAt) / 1000);
    }

    /**
     * Updates the planner's statistics for the tables just filled.
     *
     * <p>Seeding volume exists so that query plans mean something — with a
     * dozen rows PostgreSQL ignores every index, because scanning one page
     * beats any lookup. But the planner does not count rows; it reads
     * STATISTICS, and those are gathered by autovacuum, which arrives minutes
     * later and on its own schedule.
     *
     * <p>Until it does, a freshly seeded table looks empty to the planner. The
     * test asserting that a radius search uses the GiST index failed for
     * exactly this reason: the plan estimated one row and picked a cheaper
     * index, seconds after 392 listings had been inserted. The data was there;
     * the knowledge of it was not.
     *
     * <p>So: seed, then analyse, in that order, before anything asks for a
     * plan.
     */
    private void analyse() {
        log.info("Refreshing planner statistics");
        jdbc.sql("ANALYZE listings, photos, listing_stats, users").update();
    }

    /**
     * Picks the locality itself, or one of the places inside it.
     *
     * <p>Roughly half and half where sublocalities exist. Enough that a search
     * for "Chembur" genuinely has to expand to find everything — which is the
     * behaviour worth having in front of you while developing — without the
     * locality name becoming rare.
     *
     * <p>The rent baseline is looked up by area NAME, and a sublocality has no
     * entry in that map, so it falls through to the default. That is on
     * purpose: inventing a separate rent level for Diamond Garden would be
     * inventing a market.
     */
    /**
     * The rent baseline for an area, falling back to its locality.
     *
     * <p>BASE_RENT is keyed by locality name and has no entry for a
     * sublocality. Looking one up and taking the flat default would price a
     * room in Central Park at Powai money and one in Pali Hill at Kurla money —
     * rents that contradict the very area filter they are meant to make
     * testable.
     *
     * <p>Inheriting the parent's number is right for a second reason: it is
     * honest about what we know. Pali Hill genuinely is dearer than the rest of
     * Bandra West, but we have no figure for it, and inventing one would be
     * inventing a market rather than modelling it.
     */
    private static int baseRentFor(Area area) {
        Integer own = BASE_RENT.get(area.name());
        if (own != null) {
            return own;
        }
        if (area.parentName() != null) {
            return BASE_RENT.getOrDefault(area.parentName(), 22000);
        }
        return 22000;
    }

    private Area placeWithin(AreaRepository.AreaGroup group, Random random) {
        if (!group.hasChildren() || random.nextBoolean()) {
            return group.parent();
        }
        return group.children().get(random.nextInt(group.children().size()));
    }

    private void seedOne(Area area, Random random, int phoneCounter) {
        User leaver = new User(
                NAMES[random.nextInt(NAMES.length)],
                randomOf(random, Gender.MALE, Gender.FEMALE, Gender.OTHER, Gender.UNDISCLOSED),
                randomOf(random, AgeBracket.values()));
        // Deliberately NOT verifyPhone(). Since V14 a number is typed at the
        // profile step and nothing proves it, so phone_verified_at is null for
        // every real account. Seed data that set the badge would make dev look
        // unlike production in exactly the place the badge is read.
        //
        // One in three has the work-email badge, so the trust signal varies.
        if (random.nextInt(3) == 0) {
            leaver.verifyWorkEmail();
        }
        users.save(leaver);

        // .invalid is the reserved TLD (RFC 2606) that can never resolve, so a
        // misconfigured dev mail sender cannot reach a real inbox. 9000-prefixed
        // numbers are outside the ranges carriers assign, for the same reason.
        userPrivates.save(new UserPrivate(
                leaver.getId(),
                "seed-%05d@nexttenant.invalid".formatted(phoneCounter),
                "+9190000%05d".formatted(phoneCounter)));

        Configuration configuration = randomOf(random, Configuration.values());
        RoomType roomType = randomOf(random, RoomType.PRIVATE_ROOM, RoomType.PRIVATE_ROOM,
                RoomType.SHARED_ROOM, RoomType.MASTER_ROOM);
        Furnishing furnishing = randomOf(random, Furnishing.values());

        // Rent varies around the area's baseline, then by room type: a shared
        // room is cheaper than a master bedroom in the same flat.
        int base = baseRentFor(area);
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

        seedPhotos(listing.getId(), random);
    }

    /**
     * Gives a seeded listing its photographs.
     *
     * <p>These go through {@link com.househunting.photo.PhotoService#upload}
     * rather than straight into the table. That is the whole point: the seed
     * then exercises the real EXIF-stripping, resizing, thumbnailing and
     * position-assignment path, so a bug in any of it shows up as 400 broken
     * dev listings rather than waiting for a real upload to find it.
     *
     * <p>Scenes are taken as a PREFIX of the enum rather than at random, so a
     * three-photo listing gets a living room, a bedroom and a kitchen. Picking
     * randomly gives you listings whose only photographs are two bathrooms,
     * which tells you nothing about how the gallery looks in practice.
     */
    private void seedPhotos(java.util.UUID listingId, Random random) {
        int count = MIN_SEED_PHOTOS + random.nextInt(MAX_SEED_PHOTOS - MIN_SEED_PHOTOS + 1);
        SeedImages.Scene[] scenes = SeedImages.Scene.values();

        for (int position = 0; position < count; position++) {
            SeedImages.Scene scene = scenes[position % scenes.length];
            // Seed derived from the listing id, so re-seeding an identical
            // database produces identical images.
            long seed = listingId.getMostSignificantBits() ^ (position * 0x9E3779B97F4A7C15L);
            photos.upload(listingId, SeedImages.jpeg(scene, seed),
                    scene.name().toLowerCase(Locale.ROOT) + ".jpg");
        }
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
