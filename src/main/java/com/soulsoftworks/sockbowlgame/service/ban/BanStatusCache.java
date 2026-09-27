package com.soulsoftworks.sockbowlgame.service.ban;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.soulsoftworks.sockbowlgame.ratelimit.SubjectBanChecker.SubjectBan;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Local cache of "is this subject banned?" (plan m4-limits section 2.4, RL-06).
 *
 * <p>Every STOMP SEND and every guarded REST call asks this question; before
 * M4 each one was a Postgres query. Now the answer comes from the Redis mirror
 * ({@code ban:{sub}}, written by {@link BanRedisMirror}) and is cached here for
 * {@code sockbowl.bans.cache-ttl} (30s), or until the ban expires if that is
 * sooner. When Redis cannot be read the Postgres lookup is used instead
 * (A6); if that fails too the subject is treated as not banned (D12, fail
 * open). {@link com.soulsoftworks.sockbowlgame.service.BanService} invalidates
 * a subject locally on every create and remove, so the issuing instance
 * enforces a new ban at once; other instances see it within the TTL.
 *
 * <p>Time comes from the injected {@link Clock} (the Caffeine ticker reads it
 * too), so tests advance a mutable clock instead of sleeping.
 */
@Slf4j
public class BanStatusCache {

    private static final long WARN_INTERVAL_MS = 60_000;
    private static final long MAX_ENTRIES = 100_000;

    private final BanRedisMirror mirror;
    private final Function<String, Optional<SubjectBan>> databaseLookup;
    private final Clock clock;
    private final long ttlNanos;
    private final Cache<String, Optional<SubjectBan>> cache;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    /**
     * @param mirror         the Redis mirror (may be {@code null}: Postgres only)
     * @param databaseLookup the Postgres lookup used when Redis is unavailable
     * @param clock          the limits clock
     * @param ttl            how long an answer is trusted
     */
    public BanStatusCache(BanRedisMirror mirror, Function<String, Optional<SubjectBan>> databaseLookup,
                          Clock clock, Duration ttl) {
        this.mirror = mirror;
        this.databaseLookup = databaseLookup;
        this.clock = clock;
        this.ttlNanos = ttl.toNanos();
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .expireAfter(new Expiry<String, Optional<SubjectBan>>() {
                    @Override
                    public long expireAfterCreate(String key, Optional<SubjectBan> value, long currentTime) {
                        return lifetime(value);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Optional<SubjectBan> value, long currentTime,
                                                  long currentDuration) {
                        return lifetime(value);
                    }

                    @Override
                    public long expireAfterRead(String key, Optional<SubjectBan> value, long currentTime,
                                                long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /** The subject's active ban, if any. Never throws. */
    public Optional<SubjectBan> find(String sub) {
        if (sub == null || sub.isBlank()) {
            return Optional.empty();
        }
        Optional<SubjectBan> ban = cache.get(sub, this::load);
        if (ban.isPresent() && ban.get().expiresAt() != null
                && !ban.get().expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return ban;
    }

    public boolean isBanned(String sub) {
        return find(sub).isPresent();
    }

    /** Drops one subject's cached answer (after a local create or remove). */
    public void invalidate(String sub) {
        if (sub != null) {
            cache.invalidate(sub);
        }
    }

    /** Drops every cached answer (after a full resync). */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private Optional<SubjectBan> load(String sub) {
        if (mirror != null) {
            try {
                return mirror.read(sub);
            } catch (RuntimeException e) {
                warn("Redis ban mirror unreadable (" + e.getMessage() + "); falling back to Postgres");
            }
        }
        try {
            return databaseLookup.apply(sub);
        } catch (RuntimeException e) {
            warn("Postgres ban lookup failed too (" + e.getMessage() + "); failing open");
            return Optional.empty();
        }
    }

    /** Cache for the TTL, or only until the ban expires when that is sooner. */
    private long lifetime(Optional<SubjectBan> value) {
        if (value.isEmpty() || value.get().expiresAt() == null) {
            return ttlNanos;
        }
        long untilExpiry = Duration.between(clock.instant(), value.get().expiresAt()).toNanos();
        return Math.max(0, Math.min(ttlNanos, untilExpiry));
    }

    private void warn(String message) {
        long now = System.currentTimeMillis();
        long last = lastWarnMs.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(last, now)) {
            log.warn("Ban status: {}", message);
        } else {
            log.debug("Ban status: {}", message);
        }
    }
}
