package com.soulsoftworks.sockbowlgame.ratelimit;

/**
 * Records that a subject was just seen, for the admin usage view (plan
 * m4-limits section 2.4, "Last-seen IPs"). The real implementation is
 * {@code usage.UsageTracker} (WP-G5), which throttles itself to at most one
 * write per subject per minute per instance; until it is on the classpath (or
 * with a caller that has no richer identity yet), {@link #NONE} is a no-op via
 * {@link LimitsFallbackAutoConfiguration}.
 *
 * <p>Callers (the REST request guard filter, the STOMP post-auth touch guard)
 * call {@link #touch(LimitSubject)} unconditionally; implementations decide
 * their own throttling and must never let a Redis failure propagate (fail
 * open, like every other usage/quota write).
 */
public interface UsageTouchTracker {

    void touch(LimitSubject subject);

    UsageTouchTracker NONE = subject -> {
    };
}
