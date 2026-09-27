package com.soulsoftworks.sockbowlgame.service.ban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.RedisUnavailableException;
import com.soulsoftworks.sockbowlgame.ratelimit.SubjectBanChecker.SubjectBan;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes subject bans (D8, AB-01) to the shared Redis so sockbowl-questions
 * can enforce them without reading game's Postgres (plan m4-limits section 2.4).
 *
 * <p>Key {@code ban:{sub}} holds the subject's <i>effective</i> ban (the
 * permanent one if any, otherwise the one that expires last) as JSON
 * {@code {"reason":"...","expiresAt":"2026-09-28T00:00:00Z"|null}}. The key's
 * TTL is {@code expiresAt - now}; a permanent ban has no TTL. Postgres stays
 * the source of truth: {@link com.soulsoftworks.sockbowlgame.service.BanService}
 * republishes a subject after every change and resyncs the whole keyspace at
 * startup and every {@code sockbowl.bans.resync-interval}.
 *
 * <p>Writes fail open (D12): a Redis error is logged at WARN (at most once a
 * minute) and swallowed, and the next resync repairs the mirror. Reads throw
 * {@link RedisUnavailableException} so {@link BanStatusCache} can fall back to
 * Postgres.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class BanRedisMirror {

    static final String KEY_PATTERN = UsageKeys.ban("*");
    private static final long WARN_INTERVAL_MS = 60_000;

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private final RateLimitRedis redis;
    private final Clock clock;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    public BanRedisMirror(RateLimitRedis redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * Writes {@code ban:{sub}} for an active ban, or deletes it when the subject
     * has none (or the ban has already expired). Never throws.
     */
    public void publish(String sub, Optional<SubjectBan> ban) {
        if (sub == null || sub.isBlank()) {
            return;
        }
        try {
            write(redis.sync(), sub, ban.orElse(null));
        } catch (RuntimeException e) {
            warn("publish ban:" + sub, e);
        }
    }

    /**
     * Replaces every {@code ban:*} key with the given map (subject to effective
     * ban): stale keys are deleted, the rest rewritten with a fresh TTL. Never
     * throws.
     *
     * @return the number of keys written, or -1 when Redis was unavailable
     */
    public int replaceAll(Map<String, SubjectBan> bans) {
        try {
            RedisCommands<String, String> sync = redis.sync();
            List<String> stale = new ArrayList<>();
            ScanArgs args = ScanArgs.Builder.matches(KEY_PATTERN).limit(500);
            KeyScanCursor<String> cursor = sync.scan(args);
            while (true) {
                for (String key : cursor.getKeys()) {
                    String sub = key.substring(UsageKeys.ban("").length());
                    if (!bans.containsKey(sub)) {
                        stale.add(key);
                    }
                }
                if (cursor.isFinished()) {
                    break;
                }
                cursor = sync.scan(cursor, args);
            }
            if (!stale.isEmpty()) {
                sync.del(stale.toArray(String[]::new));
            }
            int written = 0;
            for (Map.Entry<String, SubjectBan> entry : bans.entrySet()) {
                if (write(sync, entry.getKey(), entry.getValue())) {
                    written++;
                }
            }
            return written;
        } catch (RuntimeException e) {
            warn("resync ban:*", e);
            return -1;
        }
    }

    /**
     * Reads {@code ban:{sub}}.
     *
     * @return the mirrored ban, or empty when the subject is not banned
     * @throws RedisUnavailableException when Redis cannot be read
     */
    public Optional<SubjectBan> read(String sub) {
        String json;
        try {
            json = redis.sync().get(UsageKeys.ban(sub));
        } catch (RedisUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RedisUnavailableException("ban mirror read failed: " + e.getMessage(), e);
        }
        if (json == null) {
            return Optional.empty();
        }
        SubjectBan ban = fromJson(json);
        if (ban.expiresAt() != null && !ban.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(ban);
    }

    /** The {@code ban:{sub}} JSON value (the cross-service contract). */
    static String toJson(SubjectBan ban) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", ban.reason());
        body.put("expiresAt", ban.expiresAt() == null ? null : ban.expiresAt().toString());
        return GSON.toJson(body);
    }

    static SubjectBan fromJson(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        String reason = object.has("reason") && !object.get("reason").isJsonNull()
                ? object.get("reason").getAsString() : null;
        Instant expiresAt = object.has("expiresAt") && !object.get("expiresAt").isJsonNull()
                ? Instant.parse(object.get("expiresAt").getAsString()) : null;
        return new SubjectBan(reason, expiresAt);
    }

    /** @return true when a key was written (false when deleted) */
    private boolean write(RedisCommands<String, String> sync, String sub, SubjectBan ban) {
        String key = UsageKeys.ban(sub);
        if (ban == null) {
            sync.del(key);
            return false;
        }
        if (ban.expiresAt() == null) {
            sync.set(key, toJson(ban));
            return true;
        }
        long ttlMs = Duration.between(clock.instant(), ban.expiresAt()).toMillis();
        if (ttlMs <= 0) {
            sync.del(key);
            return false;
        }
        sync.psetex(key, ttlMs, toJson(ban));
        return true;
    }

    private void warn(String what, RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnMs.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(last, now)) {
            log.warn("Ban mirror: {} failed ({}); Postgres stays authoritative and the next resync repairs it",
                    what, e.getMessage());
        } else {
            log.debug("Ban mirror: {} failed", what, e);
        }
    }
}
