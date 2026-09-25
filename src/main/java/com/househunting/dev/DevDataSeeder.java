package com.househunting.dev;

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
import java.util.List;

/**
 * Populates a local database with realistic Mumbai listings.
 *
 * <p>{@code @Profile("dev")} means this bean is not merely disabled in
 * production — it is never created. There is no flag to set wrongly and no
 * environment check to forget.
 *
 * <p>Deliberately NOT a Flyway migration: migrations run in every environment,
 * and fictional flats have no business in a live database.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final int LIVE_DAYS = 14;

    private final UserRepository users;
    private final UserPrivateRepository userPrivates;
    private final ListingRepository listings;
    private final ListingPrivateRepository listingPrivates;

    public DevDataSeeder(UserRepository users,
                         UserPrivateRepository userPrivates,
                         ListingRepository listings,
                         ListingPrivateRepository listingPrivates) {
        this.users = users;
        this.userPrivates = userPrivates;
        this.listings = listings;
        this.listingPrivates = listingPrivates;
    }

    /**
     * One row per seeded listing. A record keeps the data table-shaped and
     * readable, rather than twelve near-identical blocks of setter calls.
     */
    private record Seed(
            String leaverName, Gender gender, AgeBracket age, String phone,
            String area, String station, int walkMinutes,
            double latitude, double longitude,
            Configuration configuration, RoomType roomType, Furnishing furnishing,
            int rent, int deposit, Integer maintenance, int buyout,
            int flatmates, int floor, boolean lift,
            FlatmateGender openTo, int availableInDays
    ) {
    }

    /**
     * Coordinates are real Mumbai localities, so the map and radius search
     * behave the way they will in production. Phone numbers are in the
     * reserved-for-fiction range and belong to nobody.
     */
    private static final List<Seed> SEEDS = List.of(
            new Seed("Priya", Gender.FEMALE, AgeBracket.AGE_23_27, "+919000000001",
                    "Bandra West", "Bandra", 8, 19.0596, 72.8295,
                    Configuration.BHK_2, RoomType.PRIVATE_ROOM, Furnishing.SEMI_FURNISHED,
                    32000, 64000, 2500, 15000, 2, 7, true, FlatmateGender.FEMALE, 37),

            new Seed("Rahul", Gender.MALE, AgeBracket.AGE_28_32, "+919000000002",
                    "Andheri West", "Andheri", 12, 19.1364, 72.8296,
                    Configuration.BHK_3, RoomType.MASTER_ROOM, Furnishing.FULLY_FURNISHED,
                    28000, 56000, 3000, 0, 2, 11, true, FlatmateGender.ANY, 15),

            new Seed("Aisha", Gender.FEMALE, AgeBracket.AGE_23_27, "+919000000003",
                    "Powai", "Kanjurmarg", 18, 19.1176, 72.9060,
                    Configuration.BHK_2, RoomType.PRIVATE_ROOM, Furnishing.SEMI_FURNISHED,
                    24000, 48000, 2000, 8000, 1, 4, true, FlatmateGender.FEMALE, 22),

            new Seed("Vikram", Gender.MALE, AgeBracket.AGE_28_32, "+919000000004",
                    "Lower Parel", "Lower Parel", 6, 18.9960, 72.8300,
                    Configuration.BHK_2, RoomType.SHARED_ROOM, Furnishing.FULLY_FURNISHED,
                    19000, 38000, 1800, 0, 3, 14, true, FlatmateGender.MALE, 8),

            new Seed("Neha", Gender.FEMALE, AgeBracket.AGE_18_22, "+919000000005",
                    "Malad West", "Malad", 10, 19.1860, 72.8480,
                    Configuration.BHK_1, RoomType.PRIVATE_ROOM, Furnishing.UNFURNISHED,
                    16000, 32000, 1200, 0, 1, 3, false, FlatmateGender.FEMALE, 45),

            new Seed("Arjun", Gender.MALE, AgeBracket.AGE_23_27, "+919000000006",
                    "Goregaon East", "Goregaon", 14, 19.1640, 72.8560,
                    Configuration.BHK_2, RoomType.PRIVATE_ROOM, Furnishing.SEMI_FURNISHED,
                    21000, 42000, 1500, 5000, 2, 8, true, FlatmateGender.ANY, 30),

            new Seed("Sneha", Gender.FEMALE, AgeBracket.AGE_28_32, "+919000000007",
                    "Chembur", "Chembur", 9, 19.0620, 72.8990,
                    Configuration.BHK_2, RoomType.PRIVATE_ROOM, Furnishing.FULLY_FURNISHED,
                    26000, 52000, 2200, 12000, 1, 6, true, FlatmateGender.FEMALE, 18),

            new Seed("Karan", Gender.MALE, AgeBracket.AGE_33_40, "+919000000008",
                    "Thane West", "Thane", 15, 19.1860, 72.9750,
                    Configuration.BHK_3, RoomType.PRIVATE_ROOM, Furnishing.SEMI_FURNISHED,
                    18000, 36000, 1600, 0, 2, 9, true, FlatmateGender.ANY, 26),

            new Seed("Meera", Gender.FEMALE, AgeBracket.AGE_23_27, "+919000000009",
                    "Vile Parle East", "Vile Parle", 7, 19.0990, 72.8440,
                    Configuration.BHK_2, RoomType.MASTER_ROOM, Furnishing.FULLY_FURNISHED,
                    35000, 70000, 2800, 20000, 1, 12, true, FlatmateGender.FEMALE, 11),

            new Seed("Aditya", Gender.MALE, AgeBracket.AGE_23_27, "+919000000010",
                    "Borivali West", "Borivali", 11, 19.2300, 72.8560,
                    Configuration.BHK_1, RoomType.SHARED_ROOM, Furnishing.SEMI_FURNISHED,
                    13000, 26000, 1000, 0, 2, 2, false, FlatmateGender.MALE, 5),

            new Seed("Divya", Gender.FEMALE, AgeBracket.AGE_28_32, "+919000000011",
                    "Dadar West", "Dadar", 5, 19.0180, 72.8440,
                    Configuration.BHK_2, RoomType.PRIVATE_ROOM, Furnishing.SEMI_FURNISHED,
                    29000, 58000, 2400, 9000, 2, 5, true, FlatmateGender.ANY, 20),

            new Seed("Rohit", Gender.MALE, AgeBracket.AGE_23_27, "+919000000012",
                    "Kurla West", "Kurla", 13, 19.0700, 72.8790,
                    Configuration.BHK_2, RoomType.PRIVATE_ROOM, Furnishing.UNFURNISHED,
                    15000, 30000, 1100, 0, 3, 1, false, FlatmateGender.MALE, 33)
    );

    @Override
    @Transactional
    public void run(String... args) {
        if (listings.count() > 0) {
            log.info("Dev seed skipped: {} listings already present", listings.count());
            return;
        }

        log.info("Seeding {} development listings", SEEDS.size());
        SEEDS.forEach(this::seedOne);
        log.info("Dev seed complete: {} live listings", listings.count());
    }

    private void seedOne(Seed seed) {
        User leaver = new User(seed.leaverName(), seed.gender(), seed.age());
        leaver.verifyPhone();
        users.save(leaver);

        userPrivates.save(new UserPrivate(leaver.getId(), seed.phone()));

        Listing listing = new Listing(leaver.getId());
        listing.setLocationDetails(seed.area(), seed.station(),
                (short) seed.walkMinutes(), (short) seed.floor(), seed.lift());
        listing.setFlatDetails(seed.configuration(), seed.roomType(),
                HandoverType.ROOM_WITH_FURNITURE, (short) seed.flatmates(), seed.furnishing());
        listing.setAmenities(List.of("LIFT", "PARKING", "WIFI", "WASHING_MACHINE"));
        listing.setPerks(List.of("BALCONY"));
        listing.setMoney(seed.rent(), seed.deposit(), seed.maintenance(),
                false, 0, seed.buyout());
        listing.setHandoverTerms(true, DepositPaidTo.EXISTING_TENANT, true,
                AgreementHandling.TRANSFER_EXISTING, (short) 30, true);
        listing.setFlatmateRules((short) 22, (short) 32, seed.openTo(),
                "Working professionals", true, false, false, true, null);
        listing.setAvailableFrom(LocalDate.now().plusDays(seed.availableInDays()));
        listing.advanceWizardTo(6);
        // saveAndFlush, not save: the INSERT must reach the database before
        // listing_private references it, and a deferred flush would order the
        // two statements the wrong way round.
        listings.saveAndFlush(listing);

        // Saving the exact location fires the database trigger that computes
        // the public approximate point. publish() below depends on that point
        // existing, because listings_live_complete_ck requires it — so the
        // ordering here is load-bearing, not incidental.
        ListingPrivate priv = new ListingPrivate(listing.getId());
        priv.placeAt(GeoPoints.of(seed.latitude(), seed.longitude()));
        priv.setFullAddress("Flat %d02, %s Residency, %s, Mumbai"
                .formatted(seed.floor(), seed.area(), seed.area()));
        priv.setSocietyName(seed.area() + " Residency");
        listingPrivates.saveAndFlush(priv);

        listing.publish(Instant.now(), LIVE_DAYS);
        listings.save(listing);
    }
}
