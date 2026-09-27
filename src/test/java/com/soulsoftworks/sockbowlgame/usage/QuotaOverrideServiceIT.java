package com.soulsoftworks.sockbowlgame.usage;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.model.entity.QuotaOverride;
import com.soulsoftworks.sockbowlgame.quota.QuotaService;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.Tier;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.repository.QuotaOverrideRepository;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-G5 acceptance for {@link QuotaOverrideService} (D10; plan m4-limits
 * sections 2.1, 2.3, 2.8): overrides are written to Postgres first and
 * mirrored to Redis, a clear removes both, and {@link
 * QuotaOverrideService#resyncAll()} repopulates a flushed Redis mirror from
 * Postgres (the source of truth) so {@code QuotaService#effectiveLimit} sees
 * it again without Postgres being involved at read time.
 */
@Testcontainers
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        // The shared test properties exclude JPA; re-enable it against a real Postgres.
        "spring.autoconfigure.exclude=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/token",
        "spring.security.oauth2.client.registration.questions-svc.provider=keycloak",
        "spring.security.oauth2.client.registration.questions-svc.client-id=sockbowl-game-backend",
        "spring.security.oauth2.client.registration.questions-svc.client-secret=test-secret",
        "spring.security.oauth2.client.registration.questions-svc.authorization-grant-type=client_credentials",
        "sockbowl.questions.url=http://127.0.0.1:1/",
        // src/main/resources/application.properties (where WP-G1 put the real
        // sockbowl.quota.tiers.* defaults) never reaches the test classpath - a
        // same-named src/test/resources/application.properties shadows it - so
        // this mirrors application.properties' own player default (3) for the
        // "falls back to the tier default" assertion below.
        "sockbowl.quota.tiers.player.hosted-sessions=3"
})
@Import(QuotaOverrideServiceIT.ClockConfig.class)
class QuotaOverrideServiceIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("sockbowl_users");
    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private QuotaOverrideService overrides;
    @Autowired
    private QuotaOverrideRepository repository;
    @Autowired
    private RateLimitRedis rateLimitRedis;
    @Autowired
    private QuotaService quotaService;
    @Autowired
    private MutableClock clock;

    private static final String SUB = "kc-quota-override";
    private static final String METRIC = UsageKeys.HOSTED_SESSIONS;

    @BeforeEach
    void reset() {
        clock.set(Instant.parse("2026-09-27T12:00:00Z"));
        repository.deleteAll();
        rateLimitRedis.sync().flushdb();
    }

    @Test
    void setOverrideWritesPostgresThenMirrorsToRedis() {
        overrides.setOverride(SUB, METRIC, 7L, "kc-admin");

        Optional<QuotaOverride> saved = repository.findById(new com.soulsoftworks.sockbowlgame.model.entity.QuotaOverrideId(SUB, METRIC));
        assertThat(saved).isPresent();
        assertThat(saved.get().getLimitValue()).isEqualTo(7L);
        assertThat(saved.get().getUpdatedBy()).isEqualTo("kc-admin");

        assertThat(rateLimitRedis.sync().hget(UsageKeys.quotaOverride(SUB), METRIC)).isEqualTo("7");

        LimitSubject subject = new LimitSubject(SUB, "192.0.2.1", Tier.PLAYER);
        assertThat(quotaService.effectiveLimit(subject, METRIC)).isEqualTo(7L);
    }

    @Test
    void settingAgainUpdatesBothStores() {
        overrides.setOverride(SUB, METRIC, 7L, "kc-admin");
        overrides.setOverride(SUB, METRIC, 12L, "kc-admin-2");

        assertThat(repository.findAll()).hasSize(1);
        QuotaOverride updated = repository.findAll().get(0);
        assertThat(updated.getLimitValue()).isEqualTo(12L);
        assertThat(updated.getUpdatedBy()).isEqualTo("kc-admin-2");
        assertThat(rateLimitRedis.sync().hget(UsageKeys.quotaOverride(SUB), METRIC)).isEqualTo("12");
    }

    @Test
    void clearingRemovesFromBothStores() {
        overrides.setOverride(SUB, METRIC, 7L, "kc-admin");

        overrides.clearOverride(SUB, METRIC);

        assertThat(repository.findAll()).isEmpty();
        assertThat(rateLimitRedis.sync().hget(UsageKeys.quotaOverride(SUB), METRIC)).isNull();

        // Falls back to the PLAYER tier default (application.properties: 3) now
        // that the override is gone from both stores.
        LimitSubject subject = new LimitSubject(SUB, "192.0.2.1", Tier.PLAYER);
        assertThat(quotaService.effectiveLimit(subject, METRIC)).isEqualTo(3L);
    }

    @Test
    void setOverrideWithANullLimitClears() {
        overrides.setOverride(SUB, METRIC, 7L, "kc-admin");
        overrides.setOverride(SUB, METRIC, null, "kc-admin");

        assertThat(repository.findAll()).isEmpty();
        assertThat(rateLimitRedis.sync().hget(UsageKeys.quotaOverride(SUB), METRIC)).isNull();
    }

    @Test
    void resyncRepopulatesAFlushedRedisMirrorFromPostgres() {
        overrides.setOverride(SUB, METRIC, 9L, "kc-admin");
        overrides.setOverride("kc-other", "packets-owned", -1L, "kc-admin");
        assertThat(rateLimitRedis.sync().hget(UsageKeys.quotaOverride(SUB), METRIC)).isEqualTo("9");

        // Simulate a Redis restart/flush: Postgres still has both rows.
        rateLimitRedis.sync().flushdb();
        assertThat(rateLimitRedis.sync().hget(UsageKeys.quotaOverride(SUB), METRIC)).isNull();

        overrides.resyncAll();

        assertThat(rateLimitRedis.sync().hget(UsageKeys.quotaOverride(SUB), METRIC)).isEqualTo("9");
        assertThat(rateLimitRedis.sync().hget(UsageKeys.quotaOverride("kc-other"), "packets-owned")).isEqualTo("-1");

        LimitSubject subject = new LimitSubject(SUB, "192.0.2.1", Tier.PLAYER);
        assertThat(quotaService.effectiveLimit(subject, METRIC)).isEqualTo(9L);
    }

    @Test
    void findByKeycloakIdListsEveryOverrideForOneSubject() {
        overrides.setOverride(SUB, METRIC, 9L, "kc-admin");
        overrides.setOverride(SUB, "packets-owned", 400L, "kc-admin");
        overrides.setOverride("kc-other", METRIC, 1L, "kc-admin");

        List<QuotaOverride> forSub = overrides.findByKeycloakId(SUB);
        assertThat(forSub).hasSize(2)
                .extracting(QuotaOverride::getMetric)
                .containsExactlyInAnyOrder(METRIC, "packets-owned");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        MutableClock limitsTestClock() {
            return MutableClock.startingNow();
        }
    }
}
