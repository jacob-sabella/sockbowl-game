package com.soulsoftworks.sockbowlgame.usage;

import com.soulsoftworks.sockbowlgame.client.QuestionsUsageClient;
import com.soulsoftworks.sockbowlgame.client.QuestionsUsageClient.ContentCounts;
import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.response.GlobalUsage;
import com.soulsoftworks.sockbowlgame.model.response.GuestIpCount;
import com.soulsoftworks.sockbowlgame.model.response.UsageCounter;
import com.soulsoftworks.sockbowlgame.model.response.UsageDetail;
import com.soulsoftworks.sockbowlgame.model.response.UsageEvent;
import com.soulsoftworks.sockbowlgame.model.response.UserUsageSummary;
import com.soulsoftworks.sockbowlgame.quota.QuotaProperties;
import com.soulsoftworks.sockbowlgame.quota.QuotaService;
import com.soulsoftworks.sockbowlgame.quota.QuotaStatus;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.Tier;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The admin usage view's read/write model (plan m4-limits section 2.8, WP-G6,
 * M4-AD-01): {@code GET /api/v1/admin/usage[...]}, quota overrides and daily
 * resets. Pages over the Postgres {@code users} table and enriches each row
 * from the Redis keys game and questions <em>both</em> write (the
 * {@code UsageKeys} cross-service contract), plus one batched
 * {@code content-counts} call to sockbowl-questions for {@code packetsOwned}.
 *
 * <p>Every Redis read here is display-only (D12 in spirit): a failure never
 * throws, it degrades that one field (an empty map, a {@code -1} reading, or
 * an empty list) so the page still renders.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class AdminUsageService {

    /** How many of the most recent {@code rl:events} rows are scanned for "recent rejections" and detail lookups. */
    static final int EVENTS_SCAN_WINDOW = 500;
    static final int MAX_EVENTS_LIMIT = 1000;
    static final int DETAIL_EVENTS_LIMIT = 50;
    static final int TOP_GUEST_IPS = 10;

    private static final long WARN_INTERVAL_MS = 60_000;

    private final UserRepository userRepository;
    private final RateLimitRedis redis;
    private final QuotaService quotaService;
    private final QuotaProperties quotaProperties;
    private final QuotaOverrideService overrideService;
    private final HostedSessionQuota hostedSessionQuota;
    private final BanService banService;
    private final QuestionsUsageClient questionsUsageClient;
    private final Clock clock;
    private final long aiServerKeyDailyBudget;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    public AdminUsageService(UserRepository userRepository,
                             RateLimitRedis redis,
                             QuotaService quotaService,
                             QuotaProperties quotaProperties,
                             QuotaOverrideService overrideService,
                             HostedSessionQuota hostedSessionQuota,
                             BanService banService,
                             QuestionsUsageClient questionsUsageClient,
                             Clock clock,
                             @Value("${sockbowl.ai.server-key.daily-budget:200}") long aiServerKeyDailyBudget) {
        this.userRepository = userRepository;
        this.redis = redis;
        this.quotaService = quotaService;
        this.quotaProperties = quotaProperties;
        this.overrideService = overrideService;
        this.hostedSessionQuota = hostedSessionQuota;
        this.banService = banService;
        this.questionsUsageClient = questionsUsageClient;
        this.clock = clock;
        this.aiServerKeyDailyBudget = aiServerKeyDailyBudget;
    }

    /**
     * A page of users enriched with their usage (plan section 2.8's
     * {@code GET /api/v1/admin/usage}). {@code q}, when non-blank, filters by a
     * case-insensitive substring of the Keycloak id, email or display name.
     * {@code lastSeen} is accepted as a sort property (mapped onto the
     * Postgres {@code last_login_at} column, the closest durable proxy for it)
     * even though the live {@code lastSeenAt} shown per row comes from Redis.
     */
    public Page<UserUsageSummary> listUsers(Pageable pageable, String q, String bearerToken) {
        Pageable resolved = remapSort(pageable);
        Page<User> page = (q == null || q.isBlank())
                ? userRepository.findAll(resolved)
                : userRepository.findByKeycloakIdContainingIgnoreCaseOrEmailContainingIgnoreCaseOrNameContainingIgnoreCase(
                        q.trim(), q.trim(), q.trim(), resolved);
        List<User> users = page.getContent();
        List<String> subs = users.stream().map(User::getKeycloakId).toList();

        Map<String, ContentCounts> contentCounts = questionsUsageClient.contentCounts(subs, bearerToken);
        boolean questionsUnavailable = contentCounts == null;
        Map<String, ContentCounts> countsSafe = questionsUnavailable ? Map.of() : contentCounts;
        Map<String, Long> rejectionCounts = rejectionCountsBySub(subs, recentEventsRaw(EVENTS_SCAN_WINDOW));

        List<UserUsageSummary> summaries = users.stream()
                .map(u -> summaryFor(u, rejectionCounts, countsSafe, questionsUnavailable))
                .toList();
        return new PageImpl<>(summaries, resolved, page.getTotalElements());
    }

    /** {@code GET /api/v1/admin/usage/{sub}}; empty when no such user exists. */
    public Optional<UsageDetail> getDetail(String sub, String bearerToken) {
        Optional<User> userOpt = userRepository.findByKeycloakId(sub);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }
        Map<String, ContentCounts> contentCounts = questionsUsageClient.contentCounts(List.of(sub), bearerToken);
        boolean questionsUnavailable = contentCounts == null;
        Map<String, ContentCounts> countsSafe = questionsUnavailable ? Map.of() : contentCounts;

        List<UsageEvent> events = recentEventsRaw(EVENTS_SCAN_WINDOW);
        Map<String, Long> rejectionCounts = rejectionCountsBySub(List.of(sub), events);
        UserUsageSummary summary = summaryFor(userOpt.get(), rejectionCounts, countsSafe, questionsUnavailable);

        List<UsageEvent> subEvents = events.stream()
                .filter(e -> sub.equals(e.getSub()))
                .limit(DETAIL_EVENTS_LIMIT)
                .toList();

        return Optional.of(UsageDetail.builder()
                .summary(summary)
                .lastIps(safeZrevrange(UsageKeys.ips(sub)))
                .overrides(safeOverridesAsLong(sub))
                .recentEvents(subEvents)
                .hostedSessionIds(safeZrevrange(UsageKeys.sessions(UsageKeys.userPart(sub))))
                .build());
    }

    /** {@code GET /api/v1/admin/usage/global}. */
    public GlobalUsage getGlobal() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        long used = readGlobalDaily(UsageKeys.AI_SERVERKEY, today);
        UsageCounter aiCounter = UsageCounter.builder()
                .metric(UsageKeys.AI_SERVERKEY)
                .used(used)
                .limit(aiServerKeyDailyBudget)
                .kind(UsageCounter.KIND_GLOBAL_DAILY)
                .resetsAt(quotaService.nextUtcMidnight().toString())
                .overridden(false)
                .build();

        SessionScanResult scan = scanHostedSessions();
        long rejectionsLastHour = countEventsSince(now.minus(Duration.ofHours(1)));

        return GlobalUsage.builder()
                .aiServerKey(aiCounter)
                .activeHostedSessions(scan.totalActive())
                .topGuestIps(scan.topGuestIps())
                .rejectionsLastHour(rejectionsLastHour)
                .build();
    }

    /** {@code GET /api/v1/admin/usage/events}: the most recent rows, newest first. */
    public List<UsageEvent> recentEvents(int limit) {
        return recentEventsRaw(Math.max(1, Math.min(limit, MAX_EVENTS_LIMIT)));
    }

    /**
     * {@code PUT /api/v1/admin/usage/{sub}/quota/{metric}}. {@code limit} of
     * {@code null} clears the override back to the tier default. Returns the
     * counter as it reads immediately after the write; for
     * {@code packets-owned} {@code used} reads as {@code -1} here (this call
     * has no admin bearer token to relay to sockbowl-questions), matching the
     * "unavailable" convention used elsewhere on this page.
     *
     * @throws IllegalArgumentException for a metric this API doesn't know
     */
    public UsageCounter setQuotaOverride(String sub, String metric, Long limit, String updatedBy) {
        requireKnownMetric(metric);
        overrideService.setOverride(sub, metric, limit, updatedBy);
        Map<String, String> meta = safeHgetAll(UsageKeys.meta(sub));
        LimitSubject subject = new LimitSubject(sub, null, parseTier(meta.get("tier")));
        Map<String, String> overrides = safeHgetAll(UsageKeys.quotaOverride(sub));
        return counterFor(metric, subject, overrides, null, true);
    }

    /**
     * {@code POST /api/v1/admin/usage/{sub}/reset}. A blank/absent
     * {@code metric} resets every one of this subject's counters
     * ({@code hosted-sessions}'s active-session ZSET included, since that is
     * this metric's only "usage so far" state); a given metric resets just
     * that one.
     *
     * @throws IllegalArgumentException for a metric this API doesn't know
     */
    public void resetUsage(String sub, String metric) {
        Set<String> targets = (metric == null || metric.isBlank())
                ? Set.of(UsageKeys.HOSTED_SESSIONS, UsageKeys.AI_GENERATIONS, UsageKeys.IMPORTS)
                : Set.of(metric);
        for (String m : targets) {
            requireKnownMetric(m);
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (String m : targets) {
            try {
                if (UsageKeys.HOSTED_SESSIONS.equals(m)) {
                    redis.sync().del(UsageKeys.sessions(UsageKeys.userPart(sub)));
                } else {
                    redis.sync().del(UsageKeys.daily(sub, m, today));
                }
            } catch (RuntimeException e) {
                warn(e);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Row assembly                                                        */
    /* ------------------------------------------------------------------ */

    private UserUsageSummary summaryFor(User user, Map<String, Long> rejectionCounts,
                                        Map<String, ContentCounts> contentCounts, boolean questionsUnavailable) {
        String sub = user.getKeycloakId();
        Map<String, String> meta = safeHgetAll(UsageKeys.meta(sub));
        Map<String, String> overrides = safeHgetAll(UsageKeys.quotaOverride(sub));
        String tierRaw = meta.getOrDefault("tier", "unknown");
        LimitSubject subject = new LimitSubject(sub, null, parseTier(tierRaw));

        long activeSessions = safeCountActive(subject);
        boolean banned = banService.isBanned(sub);
        ContentCounts counts = contentCounts.get(sub);
        Long packetsOwned = counts == null ? null : counts.packetsOwned();
        boolean packetsUnavailable = questionsUnavailable || counts == null;

        List<UsageCounter> counters = new ArrayList<>();
        counters.add(counterFor(UsageKeys.HOSTED_SESSIONS, subject, overrides, activeSessions, false));
        counters.add(counterFor(UsageKeys.AI_GENERATIONS, subject, overrides, null, false));
        counters.add(counterFor(UsageKeys.IMPORTS, subject, overrides, null, false));
        counters.add(counterFor(UsageKeys.PACKETS_OWNED, subject, overrides,
                packetsUnavailable ? null : packetsOwned, packetsUnavailable));

        return UserUsageSummary.builder()
                .keycloakId(sub)
                .username(user.getEmail())
                .displayName(user.getName())
                .tier(tierRaw)
                .lastSeenAt(meta.get("lastSeenAt"))
                .banned(banned)
                .activeSessions(activeSessions)
                .packetsOwned(packetsUnavailable ? null : packetsOwned)
                .packetsOwnedUnavailable(packetsUnavailable)
                .counters(counters)
                .recentRejections(rejectionCounts.getOrDefault(sub, 0L))
                .build();
    }

    /**
     * Builds one {@link UsageCounter}. {@code preComputedUsed} is the already-known
     * reading for {@code hosted-sessions} (its own ZSET scan) and
     * {@code packets-owned} (the batched content-counts call); the two daily
     * metrics are read fresh from {@link QuotaService#dailyStatus}.
     */
    private UsageCounter counterFor(String metric, LimitSubject subject, Map<String, String> overrides,
                                    Long preComputedUsed, boolean unavailable) {
        boolean overridden = overrides.containsKey(metric);
        return switch (metric) {
            case UsageKeys.HOSTED_SESSIONS -> UsageCounter.builder()
                    .metric(metric)
                    .used(preComputedUsed == null ? safeCountActive(subject) : preComputedUsed)
                    .limit(quotaService.effectiveLimit(subject, metric))
                    .kind(UsageCounter.KIND_CONCURRENT)
                    .resetsAt(null)
                    .overridden(overridden)
                    .build();
            case UsageKeys.AI_GENERATIONS, UsageKeys.IMPORTS -> {
                QuotaStatus status = quotaService.dailyStatus(subject, metric);
                yield UsageCounter.builder()
                        .metric(metric)
                        .used(status.used())
                        .limit(status.limit())
                        .kind(UsageCounter.KIND_DAILY)
                        .resetsAt(status.resetsAt() == null ? null : status.resetsAt().toString())
                        .overridden(overridden)
                        .build();
            }
            case UsageKeys.PACKETS_OWNED -> UsageCounter.builder()
                    .metric(metric)
                    .used(unavailable || preComputedUsed == null ? -1 : preComputedUsed)
                    .limit(quotaService.effectiveLimit(subject, metric))
                    .kind(UsageCounter.KIND_OWNED)
                    .resetsAt(null)
                    .overridden(overridden)
                    .build();
            default -> throw new IllegalArgumentException("Unknown metric: " + metric);
        };
    }

    private static void requireKnownMetric(String metric) {
        if (metric == null || !KNOWN_METRICS.contains(metric)) {
            throw new IllegalArgumentException("Unknown metric: " + metric);
        }
    }

    private static final Set<String> KNOWN_METRICS = Set.of(
            UsageKeys.HOSTED_SESSIONS, UsageKeys.AI_GENERATIONS, UsageKeys.IMPORTS, UsageKeys.PACKETS_OWNED);

    private static Tier parseTier(String raw) {
        if (raw == null) {
            return Tier.PLAYER;
        }
        try {
            return Tier.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Tier.PLAYER;
        }
    }

    private static Pageable remapSort(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            String property = "lastSeen".equals(order.getProperty()) ? "lastLoginAt" : order.getProperty();
            orders.add(order.withProperty(property));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }

    /* ------------------------------------------------------------------ */
    /* rl:events                                                          */
    /* ------------------------------------------------------------------ */

    private List<UsageEvent> recentEventsRaw(int limit) {
        try {
            List<StreamMessage<String, String>> messages = redis.sync()
                    .xrevrange(UsageKeys.events(), Range.unbounded(), Limit.from(limit));
            return messages.stream().map(AdminUsageService::toEvent).toList();
        } catch (RuntimeException e) {
            warn(e);
            return List.of();
        }
    }

    private long countEventsSince(Instant since) {
        try {
            Range<String> range = Range.create(since.toEpochMilli() + "-0", "+");
            return redis.sync().xrange(UsageKeys.events(), range).size();
        } catch (RuntimeException e) {
            warn(e);
            return 0;
        }
    }

    private static Map<String, Long> rejectionCountsBySub(List<String> subs, List<UsageEvent> events) {
        Set<String> subSet = new HashSet<>(subs);
        Map<String, Long> counts = new HashMap<>();
        for (UsageEvent event : events) {
            if (event.getSub() != null && subSet.contains(event.getSub())) {
                counts.merge(event.getSub(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static UsageEvent toEvent(StreamMessage<String, String> message) {
        Map<String, String> body = message.getBody();
        String ts = null;
        String tsRaw = body.get("ts");
        if (tsRaw != null) {
            try {
                ts = Instant.ofEpochMilli(Long.parseLong(tsRaw.trim())).toString();
            } catch (NumberFormatException ignored) {
                // leave ts null
            }
        }
        return UsageEvent.builder()
                .id(message.getId())
                .ts(ts)
                .svc(body.get("svc"))
                .policy(body.get("policy"))
                .kind(body.get("kind"))
                .sub(body.get("sub"))
                .ip(body.get("ip"))
                .path(body.get("path"))
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* Fleet-wide hosted-session scan                                     */
    /* ------------------------------------------------------------------ */

    private record SessionScanResult(long totalActive, List<GuestIpCount> topGuestIps) {
    }

    private SessionScanResult scanHostedSessions() {
        Map<String, Long> guestCounts = new LinkedHashMap<>();
        long total = 0;
        try {
            RedisCommands<String, String> sync = redis.sync();
            double cutoffMs = clock.millis() - quotaProperties.getSessionIdleTimeout().toMillis();
            ScanArgs args = ScanArgs.Builder.matches("usage:*:sessions").limit(200);
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                KeyScanCursor<String> result = sync.scan(cursor, args);
                for (String key : result.getKeys()) {
                    sync.zremrangebyscore(key, Range.create(Double.NEGATIVE_INFINITY, cutoffMs));
                    Long count = sync.zcard(key);
                    if (count == null || count <= 0) {
                        continue;
                    }
                    total += count;
                    String owner = ownerFromSessionsKey(key);
                    if (owner != null && owner.startsWith("ip:")) {
                        guestCounts.merge(owner.substring(3), count, Long::sum);
                    }
                }
                cursor = result;
            } while (!cursor.isFinished());
        } catch (RuntimeException e) {
            warn(e);
            return new SessionScanResult(0, List.of());
        }
        List<GuestIpCount> top = guestCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(TOP_GUEST_IPS)
                .map(e -> GuestIpCount.builder().ip(e.getKey()).sessions(e.getValue()).build())
                .toList();
        return new SessionScanResult(total, top);
    }

    private static String ownerFromSessionsKey(String key) {
        String prefix = "usage:";
        String suffix = ":sessions";
        if (key.startsWith(prefix) && key.endsWith(suffix) && key.length() > prefix.length() + suffix.length()) {
            return key.substring(prefix.length(), key.length() - suffix.length());
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Fail-open Redis reads                                               */
    /* ------------------------------------------------------------------ */

    private Map<String, String> safeHgetAll(String key) {
        try {
            return redis.sync().hgetall(key);
        } catch (RuntimeException e) {
            warn(e);
            return Map.of();
        }
    }

    private List<String> safeZrevrange(String key) {
        try {
            return redis.sync().zrevrange(key, 0, -1);
        } catch (RuntimeException e) {
            warn(e);
            return List.of();
        }
    }

    private Map<String, Long> safeOverridesAsLong(String sub) {
        Map<String, String> raw = safeHgetAll(UsageKeys.quotaOverride(sub));
        Map<String, Long> out = new LinkedHashMap<>();
        raw.forEach((metric, value) -> {
            try {
                out.put(metric, Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
                // an unparsable override is dropped rather than shown wrong
            }
        });
        return out;
    }

    private long safeCountActive(LimitSubject subject) {
        try {
            return hostedSessionQuota.countActive(subject);
        } catch (RuntimeException e) {
            warn(e);
            return 0;
        }
    }

    private long readGlobalDaily(String metric, LocalDate date) {
        try {
            String value = redis.sync().get(UsageKeys.globalDaily(metric, date));
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            warn(e);
            return -1;
        }
    }

    private void warn(RuntimeException e) {
        long now = clock.millis();
        long last = lastWarnMs.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastWarnMs.compareAndSet(last, now)) {
            log.warn("Admin usage Redis read unavailable; degrading gracefully: {}", e.toString());
        } else {
            log.debug("Admin usage Redis read unavailable: {}", e.toString());
        }
    }
}
