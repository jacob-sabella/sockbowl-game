package com.soulsoftworks.sockbowlgame.usage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageTouchTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Last-seen tracking (plan m4-limits section 2.4, "Last-seen IPs"): the
 * {@code usage:{sub}:meta} hash and the {@code usage:{sub}:ips} ZSET the admin
 * usage detail reads. Called from the REST request guard filter and the STOMP
 * post-auth touch guard on every request/CONNECT, but only actually writes
 * Redis at most once per subject per minute per instance (a local Caffeine
 * cache); guests (no {@code sub}) are never tracked here.
 *
 * <p>Fails open and silent on a Redis error (D12): last-seen tracking is
 * best-effort telemetry for the admin view, never something a request should
 * fail on.
 */
@Slf4j
@Component
public class UsageTracker implements UsageTouchTracker {

    static final Duration THROTTLE = Duration.ofMinutes(1);
    static final int MAX_TRACKED_IPS = 10;
    static final Duration IPS_TTL = Duration.ofDays(30);

    private static final long WARN_INTERVAL_MS = 60_000;

    private final RateLimitRedis redis;
    private final Clock clock;
    private final Cache<String, Boolean> throttle;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    public UsageTracker(RateLimitRedis redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
        this.throttle = Caffeine.newBuilder()
                .expireAfterWrite(THROTTLE)
                .ticker(() -> clock.millis() * 1_000_000L)
                .build();
    }

    /**
     * Records that {@code subject} was just seen. A no-op for a guest (no
     * {@code sub}: there is nothing durable to key the meta/IP history on) and
     * for a subject touched within the last minute on this instance.
     */
    @Override
    public void touch(LimitSubject subject) {
        if (subject == null || subject.sub() == null) {
            return;
        }
        String sub = subject.sub();
        if (throttle.getIfPresent(sub) != null) {
            return;
        }
        throttle.put(sub, Boolean.TRUE);
        try {
            long nowMs = clock.millis();
            var sync = redis.sync();
            sync.hset(UsageKeys.meta(sub), Map.of(
                    "tier", subject.tier().configKey(),
                    "lastSeenAt", Instant.ofEpochMilli(nowMs).toString()));
            String ipsKey = UsageKeys.ips(sub);
            sync.zadd(ipsKey, nowMs, subject.ip());
            // Keep only the most recent MAX_TRACKED_IPS members.
            sync.zremrangebyrank(ipsKey, 0, -(MAX_TRACKED_IPS + 1L));
            sync.expire(ipsKey, IPS_TTL.toSeconds());
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    private void warn(RuntimeException e) {
        long now = clock.millis();
        long last = lastWarnMs.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastWarnMs.compareAndSet(last, now)) {
            log.warn("Usage-tracker Redis unavailable; skipping last-seen update: {}", e.toString());
        } else {
            log.debug("Usage-tracker Redis unavailable: {}", e.toString());
        }
    }
}
