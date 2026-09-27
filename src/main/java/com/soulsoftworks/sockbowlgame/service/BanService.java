package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.entity.BanRecord;
import com.soulsoftworks.sockbowlgame.ratelimit.SubjectBanChecker.SubjectBan;
import com.soulsoftworks.sockbowlgame.repository.BanRepository;
import com.soulsoftworks.sockbowlgame.service.ban.BanRedisMirror;
import com.soulsoftworks.sockbowlgame.service.ban.BanStatusCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service that owns the ban lifecycle. It is the only place that
 * reads/writes {@link BanRecord} state; the authorization policy consults it via
 * {@link #isBanned(String)}.
 *
 * <p>M4 (plan m4-limits section 2.4, D8, AB-01, RL-06): Postgres stays the
 * source of truth, but every change is published to Redis
 * ({@code ban:{sub}}, via {@link BanRedisMirror}) so sockbowl-questions can
 * enforce it, and the whole mirror is resynced at startup and every
 * {@code sockbowl.bans.resync-interval}. {@link #isBanned(String)} answers from
 * {@link BanStatusCache} (Redis, cached 30s, Postgres fallback) instead of a
 * Postgres query per call, which is what the per-message STOMP ban check
 * ({@code GameAuthorizationPolicy.ensureNotBanned}) goes through.
 *
 * <p>Only active when {@code sockbowl.auth.enabled=true}. When authentication is
 * disabled this bean is absent and the authorization policy treats every user as
 * un-banned.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class BanService {

    private final BanRepository banRepository;
    private final BanRedisMirror mirror;
    private final BanStatusCache statusCache;
    private final Clock clock;

    @Autowired
    public BanService(BanRepository banRepository,
                      BanRedisMirror mirror,
                      Clock clock,
                      @Value("${sockbowl.bans.cache-ttl:30s}") Duration cacheTtl) {
        this.banRepository = banRepository;
        this.mirror = mirror;
        this.clock = clock;
        this.statusCache = new BanStatusCache(mirror, this::findActiveBanInDatabase, clock, cacheTtl);
    }

    /**
     * Postgres only: no Redis mirror and no cache (unit tests of the ban
     * lifecycle itself).
     */
    public BanService(BanRepository banRepository) {
        this.banRepository = banRepository;
        this.mirror = null;
        this.clock = Clock.systemUTC();
        this.statusCache = null;
    }

    /**
     * True when the given Keycloak subject currently has an active ban.
     * Answered from {@link BanStatusCache} (no Postgres query per call).
     */
    public boolean isBanned(String keycloakId) {
        return findActiveBanCached(keycloakId).isPresent();
    }

    /**
     * The subject's effective active ban, from the cache (Redis mirror with a
     * Postgres fallback). Never throws; fails open.
     */
    public Optional<SubjectBan> findActiveBanCached(String keycloakId) {
        if (keycloakId == null || keycloakId.isBlank()) {
            return Optional.empty();
        }
        if (statusCache == null) {
            return findActiveBan(keycloakId).map(BanService::toSubjectBan);
        }
        return statusCache.find(keycloakId);
    }

    /**
     * An active ban record for a Keycloak subject, if any (Postgres).
     */
    public Optional<BanRecord> findActiveBan(String keycloakId) {
        if (keycloakId == null || keycloakId.isBlank()) {
            return Optional.empty();
        }
        return banRepository.findActiveBan(keycloakId, clock.instant());
    }

    /**
     * Create (or refresh) a ban for a user.
     *
     * @param bannedKeycloakId the subject being banned
     * @param reason           human-readable reason
     * @param bannedBy         subject of the issuing admin
     * @param expiresAt        optional expiry; {@code null} for a permanent ban
     */
    public BanRecord createBan(String bannedKeycloakId, String reason, String bannedBy, Instant expiresAt) {
        BanRecord ban = BanRecord.builder()
                .bannedKeycloakId(bannedKeycloakId)
                .reason(reason)
                .bannedBy(bannedBy)
                .createdAt(clock.instant())
                .expiresAt(expiresAt)
                .build();
        BanRecord saved = banRepository.save(ban);
        republish(bannedKeycloakId);
        return saved;
    }

    /**
     * List all currently-active bans, newest first.
     */
    public List<BanRecord> listActiveBans() {
        return banRepository.findAllActive(clock.instant());
    }

    /**
     * Remove a ban by its id. Returns {@code true} if a record was removed.
     */
    public boolean removeBan(UUID banId) {
        if (banRepository.existsById(banId)) {
            String sub = banRepository.findById(banId).map(BanRecord::getBannedKeycloakId).orElse(null);
            banRepository.deleteById(banId);
            if (sub != null) {
                republish(sub);
            }
            return true;
        }
        return false;
    }

    /**
     * Rewrites the whole {@code ban:*} mirror from Postgres (startup, then every
     * {@code sockbowl.bans.resync-interval}): repopulates a flushed or restarted
     * Redis and deletes keys for bans removed while Redis was unreachable.
     *
     * @return the number of subjects mirrored, or -1 when it could not run
     */
    public int resyncAll() {
        if (mirror == null) {
            return -1;
        }
        try {
            Map<String, SubjectBan> effective = effectiveBans(banRepository.findAllActive(clock.instant()));
            int written = mirror.replaceAll(effective);
            statusCache.invalidateAll();
            if (written >= 0) {
                log.debug("Ban mirror resynced: {} subject(s)", written);
            }
            return written;
        } catch (RuntimeException e) {
            log.warn("Ban mirror resync skipped: {}", e.getMessage());
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

    /** Recomputes one subject's effective ban, publishes it and drops the cached answer. */
    private void republish(String sub) {
        if (mirror == null) {
            return;
        }
        try {
            mirror.publish(sub, findActiveBanInDatabase(sub));
        } catch (RuntimeException e) {
            log.warn("Ban mirror publish for a subject skipped: {}", e.getMessage());
        } finally {
            statusCache.invalidate(sub);
        }
    }

    private Optional<SubjectBan> findActiveBanInDatabase(String sub) {
        return effective(banRepository.findActiveBans(sub, clock.instant()));
    }

    /** Subject to effective ban, for every subject with at least one active ban. */
    static Map<String, SubjectBan> effectiveBans(Collection<BanRecord> activeBans) {
        return activeBans.stream()
                .filter(b -> b.getBannedKeycloakId() != null && !b.getBannedKeycloakId().isBlank())
                .collect(Collectors.groupingBy(BanRecord::getBannedKeycloakId, LinkedHashMap::new,
                        Collectors.toList()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> effective(e.getValue()).orElseThrow(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * The ban that decides a subject's status: a permanent one if any (the
     * newest), otherwise the one that expires last.
     */
    static Optional<SubjectBan> effective(Collection<BanRecord> activeBans) {
        Comparator<BanRecord> order = Comparator
                .comparing((BanRecord b) -> b.getExpiresAt() == null ? Instant.MAX : b.getExpiresAt())
                .thenComparing(b -> b.getCreatedAt() == null ? Instant.MIN : b.getCreatedAt());
        return activeBans.stream().max(order).map(BanService::toSubjectBan);
    }

    private static SubjectBan toSubjectBan(BanRecord ban) {
        return new SubjectBan(ban.getReason(), ban.getExpiresAt());
    }
}
