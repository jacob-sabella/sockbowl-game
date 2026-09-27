package com.soulsoftworks.sockbowlgame.usage;

import com.soulsoftworks.sockbowlgame.model.entity.QuotaOverride;
import com.soulsoftworks.sockbowlgame.model.entity.QuotaOverrideId;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.repository.QuotaOverrideRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collections;
import java.util.List;

/**
 * Owns the {@code quota_overrides} Postgres table and its Redis mirror
 * ({@code quota:override:{sub}}, plan m4-limits sections 2.1, 2.3, 2.8).
 * Postgres is the source of truth; {@code QuotaService} only ever reads the
 * Redis mirror, so a Postgres outage never blocks a request, only admin writes
 * to it. {@link #resyncAll()} runs at startup and every
 * {@code sockbowl.quota.overrides.resync-interval} (default 5m) so a flushed
 * or restarted Redis is repopulated.
 *
 * <p>Meaningful only when {@code sockbowl.auth.enabled=true} <em>and</em> a
 * real {@link QuotaOverrideRepository} exists (JPA/Postgres configured):
 * {@link QuotaOverrideRepository} is itself gated on {@code auth.enabled=true}
 * alone (mirroring {@code BanRepository}), so plenty of existing auth-on test
 * contexts run with JPA excluded and no such bean. Rather than making this
 * bean's very existence conditional on that repository too (fragile:
 * {@code @ConditionalOnBean} on a plain component-scanned bean, not an
 * auto-configuration, isn't reliably ordered), the repository is injected
 * through an {@link ObjectProvider} and a missing one degrades every method
 * here to a no-op, exactly like a Postgres outage would (D12): unlike
 * {@code BanService}, nothing on the request path depends on this bean, so
 * there is nothing for a test that doesn't care about quota overrides to mock.
 */
@Slf4j
@Service
public class QuotaOverrideService {

    private final QuotaOverrideRepository repository;
    private final RateLimitRedis redis;
    private final Clock clock;

    public QuotaOverrideService(ObjectProvider<QuotaOverrideRepository> repositoryProvider,
                                RateLimitRedis redis, Clock clock) {
        this.repository = repositoryProvider.getIfAvailable();
        this.redis = redis;
        this.clock = clock;
        if (this.repository == null) {
            log.warn("QuotaOverrideRepository is unavailable (auth off, or JPA/Postgres not configured); "
                    + "quota overrides cannot be persisted or resynced here.");
        }
    }

    /**
     * Sets (or clears, when {@code limit} is {@code null}) the override for one
     * subject and metric, writing Postgres first and then the Redis mirror.
     * A no-op returning {@code null} when Postgres isn't configured.
     */
    @Transactional
    public QuotaOverride setOverride(String keycloakId, String metric, Long limit, String updatedBy) {
        if (limit == null) {
            clearOverride(keycloakId, metric);
            return null;
        }
        if (repository == null) {
            log.warn("Dropping quota override write for '{}'/'{}': no QuotaOverrideRepository", keycloakId, metric);
            return null;
        }
        QuotaOverrideId id = new QuotaOverrideId(keycloakId, metric);
        QuotaOverride override = repository.findById(id).orElseGet(() -> QuotaOverride.builder()
                .keycloakId(keycloakId)
                .metric(metric)
                .build());
        override.setLimitValue(limit);
        override.setUpdatedBy(updatedBy);
        override.setUpdatedAt(clock.instant());
        override = repository.save(override);
        mirror(override);
        return override;
    }

    /** Removes an override, in both Postgres and its Redis mirror. */
    @Transactional
    public void clearOverride(String keycloakId, String metric) {
        if (repository != null) {
            repository.deleteById(new QuotaOverrideId(keycloakId, metric));
        }
        try {
            redis.sync().hdel(UsageKeys.quotaOverride(keycloakId), metric);
        } catch (RuntimeException e) {
            log.warn("Could not remove quota override mirror for '{}'/'{}': {}", keycloakId, metric, e.toString());
        }
    }

    /** Every override currently set for a subject; empty when Postgres isn't configured. */
    public List<QuotaOverride> findByKeycloakId(String keycloakId) {
        return repository == null ? Collections.emptyList() : repository.findByKeycloakId(keycloakId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        resyncAll();
    }

    @Scheduled(fixedRateString = "${sockbowl.quota.overrides.resync-interval:5m}")
    public void scheduledResync() {
        resyncAll();
    }

    /** Re-writes every Postgres override to Redis; repopulates a flushed mirror. No-op without Postgres. */
    public void resyncAll() {
        if (repository == null) {
            return;
        }
        try {
            for (QuotaOverride override : repository.findAll()) {
                mirror(override);
            }
        } catch (RuntimeException e) {
            log.warn("Quota-override resync failed (Postgres or Redis unavailable): {}", e.toString());
        }
    }

    private void mirror(QuotaOverride override) {
        redis.sync().hset(UsageKeys.quotaOverride(override.getKeycloakId()),
                override.getMetric(), Long.toString(override.getLimitValue()));
    }
}
