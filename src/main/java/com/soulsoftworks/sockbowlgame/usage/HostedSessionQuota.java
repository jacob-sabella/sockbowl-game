package com.soulsoftworks.sockbowlgame.usage;

import com.soulsoftworks.sockbowlgame.quota.QuotaProperties;
import com.soulsoftworks.sockbowlgame.quota.QuotaService;
import com.soulsoftworks.sockbowlgame.ratelimit.KeyBy;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlgame.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import io.lettuce.core.Range;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The concurrent {@code hosted-sessions} quota (D10; plan m4-limits section
 * 2.3): guests are keyed by IP, authenticated users by subject, and an admin
 * (or any tier configured {@code -1}) is unlimited.
 *
 * <p>{@link #reserve} is the pre-create check
 * ({@link QuotaService#effectiveLimit} resolves overrides over the tier
 * default); {@link #recordCreated} is called once the session actually exists,
 * and {@link #touchActivity} keeps a still-active session's score fresh so it
 * never goes idle while it's being played. There is deliberately no "end
 * session" call: a session stops counting once its score falls behind
 * {@code sockbowl.quota.session-idle-timeout} or its {@code GameSession}
 * document itself is gone (both are noticed and swept the next time anyone's
 * count is checked).
 *
 * <p>Every Redis failure fails open (D12): a caller who can't be counted is let
 * through rather than refused.
 */
@Slf4j
@Component
public class HostedSessionQuota {

    /**
     * How long the {@code usage:{owner}:sessions} ZSET and the per-session
     * owner index below are kept, refreshed on every write. Matches
     * {@code GameSession}'s own Redis OM TTL ({@code @Document(timeToLive =
     * 21600)}) so the bookkeeping never outlives the session it describes by
     * much, and never needs an explicit delete.
     */
    static final long INDEX_TTL_SECONDS = 21_600;

    private static final long WARN_INTERVAL_MS = 60_000;

    private final QuotaService quotaService;
    private final QuotaProperties properties;
    private final GameSessionRepository gameSessionRepository;
    private final RateLimitRedis redis;
    private final Clock clock;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    public HostedSessionQuota(QuotaService quotaService, QuotaProperties properties,
                              GameSessionRepository gameSessionRepository, RateLimitRedis redis, Clock clock) {
        this.quotaService = quotaService;
        this.properties = properties;
        this.gameSessionRepository = gameSessionRepository;
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * Throws {@link QuotaExceededException} when {@code subject} is already at
     * (or over) its hosted-session limit. Does not reserve a slot itself -
     * call {@link #recordCreated} once the session is actually created, so a
     * request that fails after this check never leaks a phantom slot.
     */
    public void reserve(LimitSubject subject) {
        if (!quotaService.isEnabled()) {
            return;
        }
        long limit = quotaService.effectiveLimit(subject, UsageKeys.HOSTED_SESSIONS);
        if (limit == QuotaProperties.UNLIMITED) {
            return;
        }
        String owner = ownerKey(subject);
        long used;
        try {
            used = countActive(owner);
        } catch (RuntimeException e) {
            warn(e);
            return;
        }
        if (used >= limit) {
            throw new QuotaExceededException(UsageKeys.HOSTED_SESSIONS, limit, used, null, clock.instant());
        }
    }

    /** Charges a newly-created session against {@code subject}'s quota. */
    public void recordCreated(LimitSubject subject, String sessionId) {
        if (!quotaService.isEnabled()) {
            return;
        }
        String owner = ownerKey(subject);
        try {
            var sync = redis.sync();
            long now = clock.millis();
            String sessionsKey = UsageKeys.sessions(owner);
            sync.zadd(sessionsKey, now, sessionId);
            sync.expire(sessionsKey, INDEX_TTL_SECONDS);
            String ownerIndex = ownerIndexKey(sessionId);
            sync.set(ownerIndex, owner);
            sync.expire(ownerIndex, INDEX_TTL_SECONDS);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    /**
     * Bumps a hosted session's last-activity score so it keeps counting as
     * active. Called (throttled to about once a minute) from
     * {@code MessageService} for every game message the session receives.
     */
    public void touchActivity(String sessionId) {
        if (!quotaService.isEnabled()) {
            return;
        }
        try {
            var sync = redis.sync();
            String owner = sync.get(ownerIndexKey(sessionId));
            if (owner == null) {
                return;
            }
            long now = clock.millis();
            String sessionsKey = UsageKeys.sessions(owner);
            sync.zadd(sessionsKey, now, sessionId);
            sync.expire(sessionsKey, INDEX_TTL_SECONDS);
            sync.expire(ownerIndexKey(sessionId), INDEX_TTL_SECONDS);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    /** The number of currently-active hosted sessions charged to a subject. */
    public long countActive(LimitSubject subject) {
        return countActive(ownerKey(subject));
    }

    private long countActive(String owner) {
        var sync = redis.sync();
        String key = UsageKeys.sessions(owner);
        double cutoffMs = clock.millis() - properties.getSessionIdleTimeout().toMillis();
        // Members with score <= cutoff have gone idle; drop them first.
        sync.zremrangebyscore(key, Range.create(Double.NEGATIVE_INFINITY, cutoffMs));
        List<String> members = sync.zrange(key, 0, -1);
        long count = 0;
        for (String sessionId : members) {
            if (gameSessionRepository.existsById(sessionId)) {
                count++;
            } else {
                // The GameSession document is gone (played out and expired,
                // or deleted); the slot is free even though nothing told us.
                sync.zrem(key, sessionId);
            }
        }
        return count;
    }

    private static String ownerKey(LimitSubject subject) {
        return subject.keyFor(KeyBy.USER_OR_IP);
    }

    private static String ownerIndexKey(String sessionId) {
        return "usage:session-owner:" + sessionId;
    }

    private void warn(RuntimeException e) {
        long now = clock.millis();
        long last = lastWarnMs.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastWarnMs.compareAndSet(last, now)) {
            log.warn("Hosted-session quota Redis unavailable; failing open: {}", e.toString());
        } else {
            log.debug("Hosted-session quota Redis unavailable: {}", e.toString());
        }
    }
}
