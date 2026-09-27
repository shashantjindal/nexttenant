package com.nexttenant.listing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires listings past their date, hourly.
 *
 * <p>Spring's own scheduler rather than an external job runner. A cron entry or
 * a queue would be another moving part to deploy and monitor, and this is one
 * UPDATE over an indexed column — the kind of work @Scheduled exists for.
 *
 * <p>Worth knowing for later: @Scheduled runs on EVERY instance. With a second
 * app server this sweep would run twice concurrently. It is idempotent, so the
 * damage is a wasted query rather than wrong data, but the moment this runs on
 * more than one instance it needs a lock — ShedLock, or a row in the job_locks
 * table the legacy schema anticipated.
 */
@Component
public class ListingExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(ListingExpirySweep.class);

    private final ListingLifecycle lifecycle;

    public ListingExpirySweep(ListingLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Hourly, not nightly.
     *
     * <p>A listing that expired at 09:00 should stop appearing in search within
     * the hour, not at midnight. Somebody requesting a room that expired eleven
     * hours ago is a wasted message for both people.
     *
     * <p>fixedDelay rather than fixedRate: the next run starts an hour after the
     * previous one FINISHES, so a slow sweep cannot overlap itself.
     */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void sweep() {
        try {
            int expired = lifecycle.expireOverdue();
            if (expired > 0) {
                log.info("Expiry sweep: {} listings expired", expired);
            }
        } catch (RuntimeException e) {
            // A scheduled method that throws is silently never rescheduled by
            // some executors, so the failure is swallowed here deliberately and
            // logged loudly instead.
            log.error("Expiry sweep failed", e);
        }
    }
}
