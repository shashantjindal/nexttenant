package com.househunting.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock a test can move.
 *
 * <p>Without this, testing "the code expired after ten minutes" means sleeping
 * for ten minutes, which means the test is deleted within a week. With it,
 * expiry, resend cooldowns and rate windows are ordinary assertions that run in
 * microseconds.
 *
 * <p>This is the practical argument for injecting {@link Clock} rather than
 * calling {@code Instant.now()}: it is not abstraction for its own sake, it is
 * the difference between time-dependent rules being tested and being hoped for.
 */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        this.now = this.now.plus(amount);
    }

    public void setTo(Instant instant) {
        this.now = instant;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }
}
