package com.nexttenant.workemail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes {@link WorkEmailChallenge}.
 */
public interface WorkEmailChallengeRepository extends JpaRepository<WorkEmailChallenge, UUID> {

    /**
     * The newest live challenge for a user.
     *
     * <p>Newest rather than "the only one", because a person can abandon a
     * verification for one address and start another. Ordering by created_at
     * descending and taking the first means the code they are looking at right
     * now is the one that counts; an older unconsumed row simply expires.
     *
     * <p>The WHERE matches work_email_challenges_lookup_ix, which is partial on
     * consumed_at IS NULL — so the index only holds live rows and stays small
     * however many verifications the table accumulates.
     */
    Optional<WorkEmailChallenge> findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId);

    /**
     * Purges expired challenges.
     *
     * <p>Deleted rather than kept: an expired challenge has no analytical value
     * and every retained row is one more thing that can leak.
     */
    @Modifying
    @Query("DELETE FROM WorkEmailChallenge c WHERE c.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
