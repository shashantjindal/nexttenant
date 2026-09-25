package com.househunting.auth;

/**
 * What a rate-limit counter is keyed on. Mirrors otp_rate_limits_scope_ck.
 *
 * <p>Both are needed, because each alone has a gap. Limiting by PHONE stops one
 * number being spammed with messages, but an attacker cycling through a
 * thousand numbers walks straight past it — while running up a real SMS bill.
 * Limiting by IP catches that, but is defeated by a proxy pool, and would
 * otherwise punish everyone behind one office NAT.
 */
public enum RateLimitScope {
    PHONE,
    IP
}
