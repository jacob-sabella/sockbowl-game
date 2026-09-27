package com.soulsoftworks.sockbowlgame.service.ban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.soulsoftworks.sockbowlgame.model.entity.IpBan;
import com.soulsoftworks.sockbowlgame.ratelimit.IpBanChecker;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.repository.IpBanRepository;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IP/CIDR bans (D8, AB-02; plan m4-limits section 2.4), and the real
 * {@link IpBanChecker} the REST request guard and the STOMP CONNECT guard use.
 *
 * <ul>
 *   <li>Postgres ({@code ip_bans}) is the source of truth. Every ban carries a
 *       TTL of at most {@code sockbowl.ipban.max-ttl} (30d), and no range is
 *       broader than {@code /min-prefix-v4} (16) or {@code /min-prefix-v6}
 *       (48).</li>
 *   <li>Active bans are mirrored to the Redis hash {@code ipban:all}
 *       (banId to {@code {"cidr":"...","expiresAtEpochMs":N}}), which
 *       sockbowl-questions and the other game instances read.</li>
 *   <li>Lookups are in memory ({@link CidrMatcher}), refreshed from the Redis
 *       hash every {@code sockbowl.ipban.refresh-interval} (15s), falling back
 *       to Postgres when Redis is down, and keeping the last good set when both
 *       are. The issuing instance rebuilds at once after a create or remove.
 *       Expiry is checked at lookup time, so an expired ban stops matching
 *       immediately.</li>
 * </ul>
 *
 * <p>Only active when {@code sockbowl.auth.enabled=true}; with auth off the
 * no-op checker from {@code LimitsFallbackAutoConfiguration} stays.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class IpBanService implements IpBanChecker {

    /** Atomically replaces the {@code ipban:all} hash: KEYS[1], ARGV = field, value, field, value, ... */
    static final String REPLACE_SCRIPT = """
            redis.call('DEL', KEYS[1])
            for i = 1, #ARGV, 2 do
              redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            return #ARGV / 2
            """;

    private static final long WARN_INTERVAL_MS = 60_000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final IpBanRepository repository;
    private final RateLimitRedis redis;
    private final Clock clock;
    private final Duration maxTtl;
    private final int minPrefixV4;
    private final int minPrefixV6;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    private volatile CidrMatcher matcher = CidrMatcher.EMPTY;

    public IpBanService(IpBanRepository repository,
                        RateLimitRedis redis,
                        Clock clock,
                        @Value("${sockbowl.ipban.max-ttl:30d}") Duration maxTtl,
                        @Value("${sockbowl.ipban.min-prefix-v4:16}") int minPrefixV4,
                        @Value("${sockbowl.ipban.min-prefix-v6:48}") int minPrefixV6) {
        this.repository = repository;
        this.redis = redis;
        this.clock = clock;
        this.maxTtl = maxTtl;
        this.minPrefixV4 = minPrefixV4;
        this.minPrefixV6 = minPrefixV6;
    }

    /* ------------------------------------------------------------------ */
    /* IpBanChecker                                                       */
    /* ------------------------------------------------------------------ */

    @Override
    public Optional<Instant> findActiveBan(String rawAddress) {
        return matcher.match(rawAddress, clock.instant());
    }

    /* ------------------------------------------------------------------ */
    /* Admin operations                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Validates and stores a ban, mirrors it to Redis and enforces it on this
     * instance at once.
     *
     * @param cidr       an address or CIDR range
     * @param ttl        time to expiry, or {@code null} when {@code expiresAt} is given
     * @param expiresAt  expiry instant, or {@code null} when {@code ttl} is given
     * @throws IllegalArgumentException on an invalid or too broad range, or a
     *                                  missing, past or too distant expiry
     */
    public IpBan create(String cidr, String reason, String bannedBy, Duration ttl, Instant expiresAt) {
        CidrMatcher.Cidr parsed = validateRange(cidr);
        Instant now = clock.instant();
        Instant expiry = resolveExpiry(now, ttl, expiresAt);
        IpBan saved = repository.save(IpBan.builder()
                .cidr(parsed.canonical())
                .reason(reason)
                .bannedBy(bannedBy)
                .createdAt(now)
                .expiresAt(expiry)
                .build());
        try {
            redis.sync().hset(UsageKeys.ipBans(), saved.getId().toString(), mirrorValue(saved));
        } catch (RuntimeException e) {
            warn("mirroring a new IP ban to Redis failed (" + e.getMessage() + "); the next resync repairs it");
        }
        rebuildFromDatabase();
        return saved;
    }

    /** Every not-yet-expired IP ban, newest first. */
    public List<IpBan> listActive() {
        return repository.findAllActive(clock.instant());
    }

    /** Removes a ban. Returns {@code true} if a record was removed. */
    public boolean remove(UUID id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        try {
            redis.sync().hdel(UsageKeys.ipBans(), id.toString());
        } catch (RuntimeException e) {
            warn("removing an IP ban from the Redis mirror failed (" + e.getMessage()
                    + "); the next resync repairs it");
        }
        rebuildFromDatabase();
        return true;
    }

    /** Parses a range and rejects one broader than the configured minimum prefix. */
    public CidrMatcher.Cidr validateRange(String cidr) {
        CidrMatcher.Cidr parsed = CidrMatcher.Cidr.parse(cidr);
        int min = parsed.isV4() ? minPrefixV4 : minPrefixV6;
        if (parsed.prefix() < min) {
            throw new IllegalArgumentException("Range " + parsed.canonical() + " is too broad; the widest allowed is /"
                    + min + (parsed.isV4() ? " for IPv4" : " for IPv6"));
        }
        return parsed;
    }

    private Instant resolveExpiry(Instant now, Duration ttl, Instant expiresAt) {
        if ((ttl == null) == (expiresAt == null)) {
            throw new IllegalArgumentException("Give exactly one of ttlSeconds or expiresAt (IP bans must expire)");
        }
        Instant expiry = ttl != null ? now.plus(ttl) : expiresAt;
        if (!expiry.isAfter(now)) {
            throw new IllegalArgumentException("The expiry must be in the future");
        }
        if (expiry.isAfter(now.plus(maxTtl))) {
            throw new IllegalArgumentException("IP bans may last at most " + maxTtl.toDays() + " days");
        }
        return expiry;
    }

    /** Parses an ISO-8601 instant from a request, as an {@link IllegalArgumentException} on bad input. */
    public static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt must be an ISO-8601 instant such as 2026-09-28T12:00:00Z");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Mirror and refresh                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Reloads the in-memory set from the Redis mirror (so bans made on other
     * instances apply here), or from Postgres when Redis is unavailable.
     * Expired entries found in the mirror are dropped from it.
     */
    @Scheduled(fixedDelayString = "${sockbowl.ipban.refresh-interval:15s}",
            initialDelayString = "${sockbowl.ipban.refresh-interval:15s}")
    public void refresh() {
        try {
            RedisCommands<String, String> sync = redis.sync();
            Map<String, String> mirrored = sync.hgetall(UsageKeys.ipBans());
            Instant now = clock.instant();
            List<CidrMatcher.Entry> entries = new ArrayList<>();
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, String> e : mirrored.entrySet()) {
                CidrMatcher.Entry entry = parseMirrorValue(e.getKey(), e.getValue());
                if (entry == null) {
                    continue;
                }
                if (entry.expiresAt().isAfter(now)) {
                    entries.add(entry);
                } else {
                    expired.add(e.getKey());
                }
            }
            if (!expired.isEmpty()) {
                sync.hdel(UsageKeys.ipBans(), expired.toArray(String[]::new));
            }
            matcher = CidrMatcher.of(entries);
        } catch (RuntimeException e) {
            warn("IP ban mirror unreadable (" + e.getMessage() + "); loading from Postgres");
            rebuildFromDatabase();
        }
    }

    /**
     * Rewrites the {@code ipban:all} mirror from Postgres (startup, then every
     * {@code sockbowl.bans.resync-interval}) and reloads the local set.
     *
     * @return the number of bans mirrored, or -1 when Redis was unavailable
     */
    public int resyncAll() {
        List<IpBan> active;
        try {
            active = repository.findAllActive(clock.instant());
        } catch (RuntimeException e) {
            warn("IP ban resync skipped: Postgres unavailable (" + e.getMessage() + ")");
            return -1;
        }
        matcher = CidrMatcher.of(toEntries(active));
        List<String> argv = new ArrayList<>(active.size() * 2);
        for (IpBan ban : active) {
            argv.add(ban.getId().toString());
            argv.add(mirrorValue(ban));
        }
        try {
            Long written = redis.sync().eval(REPLACE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{UsageKeys.ipBans()}, argv.toArray(String[]::new));
            return written == null ? 0 : written.intValue();
        } catch (RuntimeException e) {
            warn("IP ban mirror resync failed (" + e.getMessage() + ")");
            return -1;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void resyncOnStartup() {
        resyncAll();
    }

    @Scheduled(fixedDelayString = "${sockbowl.bans.resync-interval:5m}",
            initialDelayString = "${sockbowl.bans.resync-interval:5m}")
    void resyncPeriodically() {
        resyncAll();
    }

    /** Number of bans currently enforced from memory (diagnostics, tests). */
    public int enforcedCount() {
        return matcher.size();
    }

    private void rebuildFromDatabase() {
        try {
            matcher = CidrMatcher.of(toEntries(repository.findAllActive(clock.instant())));
        } catch (RuntimeException e) {
            warn("IP ban reload from Postgres failed (" + e.getMessage() + "); keeping the last known set");
        }
    }

    private List<CidrMatcher.Entry> toEntries(List<IpBan> bans) {
        List<CidrMatcher.Entry> entries = new ArrayList<>(bans.size());
        for (IpBan ban : bans) {
            try {
                entries.add(new CidrMatcher.Entry(String.valueOf(ban.getId()),
                        CidrMatcher.Cidr.parse(ban.getCidr()), ban.getExpiresAt()));
            } catch (RuntimeException e) {
                log.warn("Ignoring an unparseable IP ban {}: {}", ban.getId(), e.getMessage());
            }
        }
        return entries;
    }

    /** The {@code ipban:all} field value (the cross-service contract). */
    static String mirrorValue(IpBan ban) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("cidr", ban.getCidr());
        value.put("expiresAtEpochMs", ban.getExpiresAt().toEpochMilli());
        return GSON.toJson(value);
    }

    static CidrMatcher.Entry parseMirrorValue(String id, String json) {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            return new CidrMatcher.Entry(id,
                    CidrMatcher.Cidr.parse(object.get("cidr").getAsString()),
                    Instant.ofEpochMilli(object.get("expiresAtEpochMs").getAsLong()));
        } catch (RuntimeException e) {
            log.warn("Ignoring a malformed ipban:all entry {}: {}", id, e.getMessage());
            return null;
        }
    }

    private void warn(String message) {
        long now = System.currentTimeMillis();
        long last = lastWarnMs.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(last, now)) {
            log.warn("IP bans: {}", message);
        } else {
            log.debug("IP bans: {}", message);
        }
    }
}
