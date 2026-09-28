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
import io.lettuce.core.ScriptOutputType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The concurrent {@code hosted-sessions} quota (D10; plan m4-limits section
 * 2.3): guests are keyed by IP, authenticated users by subject, and an admin
 * (or any tier configured {@code -1}) is unlimited.
 *
 * <p>{@link #reserve} is the pre-create check, and atomically claims a slot
 * ({@code quota/hosted-session-reserve.lua}: sweep idle members, {@code ZCARD},
 * and {@code ZADD} a provisional {@code rsv:{uuid}} member only if the count is
 * still under the limit) so two concurrent requests from the same caller can
 * never both pass the check (G-M4-V1-01; that used to be a plain
 * check-then-act race). {@link #recordCreated} swaps the provisional member
 * for the real session id once the session actually exists; {@link #release}
 * gives the slot back when session creation fails after a successful reserve.
 * {@link #touchActivity} keeps a still-active session's score fresh so it
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

    static final String RESERVE_SCRIPT = loadScript("quota/hosted-session-reserve.lua");

    /** Prefix of a provisional {@link #reserve} member, e.g. {@code rsv:<uuid>}. */
    private static final String RESERVATION_PREFIX = "rsv:";

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
     * A slot claimed by {@link #reserve}: {@code owner} and the provisional
     * {@code rsv:{uuid}} ZSET member to swap out ({@link #recordCreated}) or
     * remove ({@link #release}). {@link #NONE} means no slot was taken
     * (quotas disabled, the caller is unlimited, or Redis was unavailable so
     * the call failed open) - {@link #recordCreated} and {@link #release} are
     * both no-ops for it beyond their own bookkeeping.
     */
    public record Reservation(String owner, String token) {
        static final Reservation NONE = new Reservation(null, null);
    }

    /**
     * Atomically claims a hosted-session slot for {@code subject}, throwing
     * {@link QuotaExceededException} when it is already at (or over) its
     * limit. On success, the caller must eventually call either
     * {@link #recordCreated} (session created) or {@link #release} (creation
     * failed), so a claimed slot never leaks.
     */
    public Reservation reserve(LimitSubject subject) {
        if (!quotaService.isEnabled()) {
            return Reservation.NONE;
        }
        long limit = quotaService.effectiveLimit(subject, UsageKeys.HOSTED_SESSIONS);
        if (limit == QuotaProperties.UNLIMITED) {
            return Reservation.NONE;
        }
        String owner = ownerKey(subject);
        try {
            // Liveness sweep first (existsById checks against Redis OM), so a
            // session whose GameSession document is already gone doesn't hold
            // a slot the atomic script below would otherwise refuse on. This
            // itself isn't atomic with the script, but the script re-checks
            // the count right before reserving, so the worst case is a slot
            // that frees up a moment later than it could have.
            countActive(owner);
        } catch (RuntimeException e) {
            warn(e);
            return Reservation.NONE;
        }
        String token = RESERVATION_PREFIX + UUID.randomUUID();
        long now = clock.millis();
        long cutoffMs = now - properties.getSessionIdleTimeout().toMillis();
        List<Object> result;
        try {
            result = redis.sync().eval(RESERVE_SCRIPT, ScriptOutputType.MULTI,
                    new String[]{UsageKeys.sessions(owner)},
                    Long.toString(now), Long.toString(cutoffMs), Long.toString(limit), token,
                    Long.toString(INDEX_TTL_SECONDS));
        } catch (RuntimeException e) {
            warn(e);
            return Reservation.NONE;
        }
        boolean allowed = ((Number) result.get(0)).longValue() == 1;
        long used = ((Number) result.get(1)).longValue();
        if (!allowed) {
            throw new QuotaExceededException(UsageKeys.HOSTED_SESSIONS, limit, used, null, clock.instant());
        }
        return new Reservation(owner, token);
    }

    /**
     * Charges a newly-created session against {@code subject}'s quota,
     * swapping out the {@code reservation}'s provisional member (if any -
     * {@link Reservation#NONE} for an unlimited/disabled/fail-open caller
     * still records the session itself, just without a slot to swap).
     */
    public void recordCreated(LimitSubject subject, String sessionId, Reservation reservation) {
        if (!quotaService.isEnabled()) {
            return;
        }
        String owner = ownerKey(subject);
        try {
            var sync = redis.sync();
            long now = clock.millis();
            String sessionsKey = UsageKeys.sessions(owner);
            if (reservation != null && reservation.token() != null) {
                sync.zrem(sessionsKey, reservation.token());
            }
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
     * Gives back a slot claimed by {@link #reserve} when session creation
     * failed after a successful reservation. A no-op for {@link Reservation#NONE}.
     */
    public void release(Reservation reservation) {
        if (reservation == null || reservation.token() == null) {
            return;
        }
        try {
            redis.sync().zrem(UsageKeys.sessions(reservation.owner()), reservation.token());
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
            if (sessionId.startsWith(RESERVATION_PREFIX)) {
                // Another in-flight reserve()'s own provisional slot
                // (recordCreated hasn't swapped it for the real session id
                // yet, or release() hasn't removed it after a failed create).
                // existsById would always be false for it since it was never a
                // GameSession document, so it must never be treated as a stale
                // entry here - doing so would let a concurrent reserve() sweep
                // away another caller's still-valid claim and admit over the
                // limit (G-M4-V1-01). It still counts toward the total; only
                // the idle sweep above, recordCreated, or release ever clear it.
                count++;
                continue;
            }
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

    private static String loadScript(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + path, e);
        }
    }
}
