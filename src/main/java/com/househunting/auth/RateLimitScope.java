package com.househunting.auth;

/**
 * What a rate-limit counter is keyed on. Mirrors otp_rate_limits_scope_ck.
 *
 * <p>A subject scope and an IP scope are both needed, because each alone has a
 * gap. Limiting by subject stops one person being spammed, but an attacker
 * cycling through a thousand subjects walks straight past it — while running up
 * a real bill. Limiting by IP catches that, but is defeated by a proxy pool and
 * would otherwise punish everyone behind one office NAT. Together they are
 * awkward to evade without being expensive to a legitimate user.
 */
public enum RateLimitScope {

    /** One phone number. Its budget is sized by what an SMS costs. */
    PHONE,

    /**
     * One email address.
     *
     * <p>Separate from PHONE rather than sharing a "subject" bucket: an email
     * send costs a fraction of an SMS, so the two want different budgets. A
     * single shared limit would have to be tuned for the expensive channel,
     * making the cheap one needlessly stingy.
     */
    EMAIL,

    /** One client address, as far as X-Forwarded-For can be trusted. */
    IP
}
