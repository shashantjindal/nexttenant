package com.househunting.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    /**
     * The newest live challenge for a subject.
     *
     * <p>Newest wins: if someone requested a code twice, the second supersedes
     * the first. Matching an older one would let a code the user has already
     * replaced still work.
     */
    Optional<OtpChallenge> findFirstBySubjectHashAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String subjectHash, OtpPurpose purpose);

    /**
     * Deletes expired challenges.
     *
     * <p>Deleted rather than archived. They have no analytical value, and every
     * retained row holding a hash is one more thing that can leak.
     */
    @Modifying
    @Query("DELETE FROM OtpChallenge c WHERE c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    long countBySubjectHashAndConsumedAtIsNull(String subjectHash);
}
