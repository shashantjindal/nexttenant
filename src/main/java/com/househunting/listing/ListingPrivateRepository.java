package com.househunting.listing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Exact locations and addresses.
 *
 * <p>Every method here returns data that must not reach a public page, so a
 * call to this repository should be visible and justified at the call site.
 * Kept separate from {@link ListingRepository} precisely so that reaching a
 * real address is an explicit act rather than a field access on an object the
 * caller already had.
 *
 * <p>Saving here fires the trigger that recomputes the public approximate
 * point; no Java code performs that calculation, so none can skip it.
 */
@Repository
public interface ListingPrivateRepository extends JpaRepository<ListingPrivate, UUID> {
}
