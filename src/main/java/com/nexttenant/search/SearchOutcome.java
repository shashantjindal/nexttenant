package com.nexttenant.search;

/**
 * How a result set was arrived at.
 *
 * <p>The design does not just say "no results" — it widens the radius and says
 * so, then relaxes the filters and says so. Each of those is a different query
 * with a different message, and the page has to know which one it is showing.
 */
public enum SearchOutcome {
    /** The search as asked. */
    EXACT,
    /** Nothing within the requested radius, so it was widened. */
    WIDENED,
    /** Still nothing, so the filters were dropped and only the place kept. */
    RELAXED,
    /** Nothing at all, even relaxed. */
    EMPTY
}
