package com.househunting.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips the user entities against a real PostgreSQL.
 *
 * <p>{@code @DataJpaTest} loads only the JPA slice — entities, repositories and
 * a DataSource — rather than the whole application, so it starts in about a
 * second. Each test runs in a transaction that is rolled back afterwards, so
 * the tests leave no data behind and can run in any order.
 *
 * <p>{@code replace = NONE} is the important part. By default
 * {@code @DataJpaTest} swaps the DataSource for an in-memory H2. H2's
 * "PostgreSQL mode" is a different database wearing a hat: no PostGIS, different
 * array semantics, different constraint behaviour. Testing against it would
 * mean testing a database we do not ship, and hiding precisely the bugs worth
 * finding.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPrivateRepository userPrivateRepository;

    @Test
    @DisplayName("a user round-trips, with enums stored as names and timestamps set by the database")
    void userRoundTrips() {
        User user = new User("Priya", Gender.FEMALE, AgeBracket.AGE_23_27);
        UUID id = user.getId();

        userRepository.save(user);

        // flush() sends the INSERT; clear() empties the persistence context so
        // the next find() is a genuine SELECT rather than a cache hit. Without
        // this the test would pass even if the mapping were broken.
        entityManager.flush();
        entityManager.clear();

        User loaded = userRepository.findById(id).orElseThrow();

        assertThat(loaded.getFirstName()).isEqualTo("Priya");
        assertThat(loaded.getGender()).isEqualTo(Gender.FEMALE);
        assertThat(loaded.getAgeBracket()).isEqualTo(AgeBracket.AGE_23_27);
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.ACTIVE);

        // Proves @Generated worked: Java never set these, the database did,
        // and Hibernate read them back.
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();

        assertThat(loaded.isPhoneVerified()).isFalse();
    }

    @Test
    @DisplayName("enums are persisted as names, not ordinals")
    void enumsArePersistedAsStrings() {
        User user = new User("Rahul", Gender.MALE, AgeBracket.AGE_28_32);
        userRepository.save(user);
        entityManager.flush();

        // Read the raw column. If @Enumerated(STRING) were missing this would
        // be an integer, and reordering the enum later would silently redefine
        // every existing row.
        String stored = (String) entityManager.getEntityManager()
                .createNativeQuery("SELECT gender FROM users WHERE id = :id")
                .setParameter("id", user.getId())
                .getSingleResult();

        assertThat(stored).isEqualTo("MALE");
    }

    @Test
    @DisplayName("UserPrivate derives the phone hash so the pair can never disagree")
    void phoneHashIsDerived() {
        User user = userRepository.save(new User("Aisha", Gender.FEMALE, AgeBracket.AGE_18_22));
        UserPrivate priv = new UserPrivate(user.getId(), "+919876543210");

        // 64 lowercase hex characters, and equal to an independently computed
        // digest of the same input.
        assertThat(priv.getPhoneHash())
                .matches("^[0-9a-f]{64}$")
                .isEqualTo(PhoneHash.of("+919876543210"));

        userPrivateRepository.save(priv);
        entityManager.flush();
        entityManager.clear();

        Optional<UserPrivate> found = userPrivateRepository.findByPhoneHash(priv.getPhoneHash());
        assertThat(found).isPresent();
        assertThat(found.get().getPhoneE164()).isEqualTo("+919876543210");
        assertThat(found.get().getUserId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("the same number always produces the same hash, so lookups are stable")
    void hashIsDeterministic() {
        UserPrivate a = new UserPrivate(UUID.randomUUID(), "+919876543210");
        UserPrivate b = new UserPrivate(UUID.randomUUID(), "+919876543210");

        assertThat(a.getPhoneHash()).isEqualTo(b.getPhoneHash());
    }

    @Test
    @DisplayName("changing the number recomputes the hash in the same step")
    void changingNumberRecomputesHash() {
        UserPrivate priv = new UserPrivate(UUID.randomUUID(), "+919876543210");
        String before = priv.getPhoneHash();

        priv.setPhoneE164("+919000000001");

        assertThat(priv.getPhoneHash()).isNotEqualTo(before);
        assertThat(priv.getPhoneHash()).isEqualTo(PhoneHash.of("+919000000001"));
    }

    @Test
    @DisplayName("malformed phone numbers are rejected before they reach the database")
    void rejectsMalformedPhoneNumbers() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new UserPrivate(id, "9876543210"))
                .isInstanceOf(IllegalArgumentException.class);   // no +91

        assertThatThrownBy(() -> new UserPrivate(id, "+915876543210"))
                .isInstanceOf(IllegalArgumentException.class);   // starts with 5

        assertThatThrownBy(() -> new UserPrivate(id, "+9198765432100"))
                .isInstanceOf(IllegalArgumentException.class);   // eleven digits

        assertThatThrownBy(() -> new UserPrivate(id, "+91 98765 43210"))
                .isInstanceOf(IllegalArgumentException.class);   // not normalised
    }

    @Test
    @DisplayName("the exception message never contains the phone number")
    void exceptionMessageOmitsTheNumber() {
        // Exception messages reach log files, and a phone number in a log file
        // is exactly the leak UserPrivate exists to prevent.
        assertThatThrownBy(() -> new UserPrivate(UUID.randomUUID(), "+915876543210"))
                .hasMessageNotContaining("5876543210");
    }

    @Test
    @DisplayName("business methods record verification rather than exposing setters")
    void verificationIsRecordedThroughBehaviour() {
        User user = new User("Vikram", Gender.MALE, AgeBracket.AGE_33_40);

        assertThat(user.isPhoneVerified()).isFalse();
        user.verifyPhone();
        assertThat(user.isPhoneVerified()).isTrue();

        assertThat(user.isActive()).isTrue();
        user.suspend();
        assertThat(user.isActive()).isFalse();
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }
}
