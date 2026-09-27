package com.soulsoftworks.sockbowlgame.ratelimit;

import com.soulsoftworks.sockbowlgame.quota.QuotaProperties;
import com.soulsoftworks.sockbowlgame.quota.QuotaService;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The shipped {@code application.properties} binds to the plan's policy and
 * quota tables (m4-limits sections 2.2, 2.3, 2.10), the env placeholders the
 * compose overlays rely on work, and the limiter beans wire up with a
 * replaceable {@link Clock}.
 */
class LimitsConfigurationTest {

    /** The test classpath shadows the main application.properties, so read the file itself. */
    private static final String MAIN_PROPERTIES = "src/main/resources/application.properties";

    @Test
    void shippedPoliciesMatchThePlan() throws IOException {
        RateLimitProperties rl = bind(Map.of()).rl();

        assertThat(rl.isEnabled()).isTrue();
        assertThat(rl.getRedis().getTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(rl.getServiceClients()).containsExactly("sockbowl-game-backend");
        assertThat(rl.getExempt()).containsExactly("/actuator/health", "/actuator/health/**");
        assertThat(rl.getTierMultipliers()).containsEntry(Tier.GUEST, 1.0).containsEntry(Tier.PLAYER, 1.0)
                .containsEntry(Tier.AUTHOR, 2.0).containsEntry(Tier.MODERATOR, 3.0).containsEntry(Tier.ADMIN, 10.0);

        assertPolicy(rl, "default", 120, 120, Duration.ofMinutes(1), KeyBy.USER_OR_IP);
        assertPolicy(rl, "service", 6000, 6000, Duration.ofMinutes(1), KeyBy.USER);
        assertPolicy(rl, "session-create", 5, 5, Duration.ofMinutes(10), KeyBy.USER_OR_IP);
        assertThat(rl.multiplierFor(rl.policy("session-create"), Tier.GUEST)).isEqualTo(0.6);
        assertThat(rl.multiplierFor(rl.policy("session-create"), Tier.AUTHOR)).isEqualTo(2.0);
        assertThat(BucketConfigurations.capacityOf(new BucketConfigurations(rl)
                .forPolicy("session-create", rl.policy("session-create"), Tier.GUEST))).isEqualTo(3);
        assertPolicy(rl, "session-join", 20, 20, Duration.ofMinutes(1), KeyBy.IP);
        assertPolicy(rl, "used-questions", 30, 30, Duration.ofMinutes(1), KeyBy.USER);
        assertPolicy(rl, "admin", 120, 120, Duration.ofMinutes(1), KeyBy.USER);
        assertPolicy(rl, "ws-connect", 10, 10, Duration.ofMinutes(1), KeyBy.IP);
        assertPolicy(rl, "stomp-send", 40, 20, Duration.ofSeconds(1), KeyBy.CONNECTION);
        assertPolicy(rl, "stomp-send-ip", 120, 60, Duration.ofSeconds(1), KeyBy.IP);
        assertPolicy(rl, "stomp-buzz", 5, 3, Duration.ofSeconds(1), KeyBy.CONNECTION);
        assertPolicy(rl, "stomp-flood", 50, 50, Duration.ofSeconds(10), KeyBy.CONNECTION);
        assertThat(rl.getPolicies().values()).noneMatch(PolicySpec::isFailClosed);

        assertThat(rl.getRoutes()).extracting(RateLimitProperties.Route::getPattern).containsExactly(
                "/api/v1/session/create-new-game-session",
                "/api/v1/session/join-game-session-by-code",
                "/api/v1/session/join-game-session-authenticated",
                "/api/v1/user/used-questions",
                "/api/v1/admin/**");
        assertThat(rl.getRoutes().get(0).getMethod()).isEqualTo("POST");
        assertThat(rl.getRoutes().get(0).getPolicies()).containsExactly("session-create");
        assertThat(rl.getRoutes().get(2).getPolicies()).containsExactly("session-join");
        assertThat(rl.getRoutes().get(4).getMethod()).isNull();
        assertThat(rl.getRoutes().get(4).getPolicies()).containsExactly("admin");
    }

    @Test
    void shippedQuotasMatchD10() throws IOException {
        QuotaProperties q = bind(Map.of()).quota();

        assertThat(q.isEnabled()).isTrue();
        assertThat(q.getSessionIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(q.defaultLimit(Tier.GUEST, "hosted-sessions")).isEqualTo(2);
        assertThat(q.defaultLimit(Tier.PLAYER, "hosted-sessions")).isEqualTo(3);
        assertThat(q.defaultLimit(Tier.AUTHOR, "hosted-sessions")).isEqualTo(5);
        assertThat(q.defaultLimit(Tier.MODERATOR, "hosted-sessions")).isEqualTo(5);
        assertThat(q.defaultLimit(Tier.ADMIN, "hosted-sessions")).isEqualTo(-1);
        assertThat(q.defaultLimit(Tier.GUEST, "ai.generations")).isZero();
        assertThat(q.defaultLimit(Tier.PLAYER, "ai.generations")).isZero();
        assertThat(q.defaultLimit(Tier.AUTHOR, "ai.generations")).isEqualTo(20);
        assertThat(q.defaultLimit(Tier.MODERATOR, "ai.generations")).isEqualTo(20);
        assertThat(q.defaultLimit(Tier.AUTHOR, "imports")).isEqualTo(10);
        assertThat(q.defaultLimit(Tier.AUTHOR, "packets-owned")).isEqualTo(300);
        assertThat(q.defaultLimit(Tier.PLAYER, "packets-owned")).isZero();
        assertThat(q.defaultLimit(Tier.ADMIN, "packets-owned")).isEqualTo(-1);
        // Unconfigured pairs deny, except for the quota-exempt tiers.
        assertThat(q.defaultLimit(Tier.AUTHOR, "no-such-metric")).isZero();
        assertThat(q.defaultLimit(Tier.SERVICE, "hosted-sessions")).isEqualTo(-1);
    }

    @Test
    void composeOverlayEnvPlaceholdersOverrideTheDefaults() throws IOException {
        Bound bound = bind(Map.ofEntries(
                Map.entry("SOCKBOWL_RATELIMIT_ENABLED", "false"),
                Map.entry("SOCKBOWL_QUOTA_ENABLED", "false"),
                Map.entry("SOCKBOWL_RL_DEFAULT_CAPACITY", "10000"),
                Map.entry("SOCKBOWL_RL_SESSION_CREATE_CAPACITY", "2"),
                Map.entry("SOCKBOWL_RL_SESSION_CREATE_REFILL_PERIOD", "20s"),
                Map.entry("SOCKBOWL_RL_SESSION_JOIN_CAPACITY", "1000"),
                Map.entry("SOCKBOWL_RL_WS_CONNECT_CAPACITY", "1000"),
                Map.entry("SOCKBOWL_RL_STOMP_SEND_IP_CAPACITY", "10000"),
                Map.entry("SOCKBOWL_RL_STOMP_BUZZ_CAPACITY", "3"),
                Map.entry("SOCKBOWL_RL_SERVICE_CLIENTS", "a,b"),
                Map.entry("SOCKBOWL_QUOTA_GUEST_HOSTED_SESSIONS", "100"),
                Map.entry("SOCKBOWL_QUOTA_PLAYER_HOSTED_SESSIONS", "101"),
                Map.entry("SOCKBOWL_QUOTA_AUTHOR_HOSTED_SESSIONS", "102")));
        RateLimitProperties rl = bound.rl();
        QuotaProperties q = bound.quota();

        assertThat(rl.isEnabled()).isFalse();
        assertThat(q.isEnabled()).isFalse();
        assertThat(rl.policy("default").getCapacity()).isEqualTo(10000);
        assertThat(rl.policy("session-create").getCapacity()).isEqualTo(2);
        assertThat(rl.policy("session-create").getRefillPeriod()).isEqualTo(Duration.ofSeconds(20));
        assertThat(rl.policy("session-join").getCapacity()).isEqualTo(1000);
        assertThat(rl.policy("ws-connect").getCapacity()).isEqualTo(1000);
        assertThat(rl.policy("stomp-send-ip").getCapacity()).isEqualTo(10000);
        assertThat(rl.policy("stomp-buzz").getCapacity()).isEqualTo(3);
        assertThat(rl.getServiceClients()).containsExactly("a", "b");
        assertThat(q.defaultLimit(Tier.GUEST, "hosted-sessions")).isEqualTo(100);
        assertThat(q.defaultLimit(Tier.PLAYER, "hosted-sessions")).isEqualTo(101);
        assertThat(q.defaultLimit(Tier.AUTHOR, "hosted-sessions")).isEqualTo(102);
    }

    @Test
    void serviceClientsDefaultToTheAuthServiceClientId() throws IOException {
        assertThat(bind(Map.of("KEYCLOAK_GAME_BACKEND_CLIENT_ID", "custom-backend")).rl().getServiceClients())
                .containsExactly("custom-backend");
    }

    @Test
    void shippedConfigPassesTheStartupValidator() throws IOException {
        Bound bound = bind(Map.of());
        assertThatCode(() -> new LimitsStartupValidator(bound.env(), bound.rl()).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThat(bound.env().getProperty("server.forward-headers-strategy")).isEqualTo("none");
    }

    @Test
    void nativeStrategyFromEnvWithoutAProxyRegexFailsTheValidator() throws IOException {
        Bound bound = bind(Map.of("SOCKBOWL_FORWARD_HEADERS_STRATEGY", "native"));
        assertThat(bound.env().getProperty("server.tomcat.remoteip.internal-proxies")).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new LimitsStartupValidator(bound.env(), bound.rl()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);

        Bound trusted = bind(Map.of("SOCKBOWL_FORWARD_HEADERS_STRATEGY", "native",
                "SOCKBOWL_TRUSTED_PROXIES_REGEX", "10\\.0\\.0\\.2"));
        assertThatCode(() -> new LimitsStartupValidator(trusted.env(), trusted.rl()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    // --- bean wiring -------------------------------------------------------------

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LimitsClockConfig.class, LimitsFallbackAutoConfiguration.class))
            .withUserConfiguration(RateLimitRedisConfig.class)
            .withPropertyValues("sockbowl.redis.game-cache.port=1");

    @Test
    void wiresTheLimiterWithASystemClockAndNoOpBanCheckersByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(RateLimitService.class).hasSingleBean(LocalBucketRegistry.class)
                    .hasSingleBean(RateLimitEventRecorder.class).hasSingleBean(QuotaService.class);
            assertThat(ctx.getBean(Clock.class)).isEqualTo(Clock.systemUTC());
            assertThat(ctx.getBean(IpBanChecker.class)).isSameAs(IpBanChecker.NONE);
            assertThat(ctx.getBean(SubjectBanChecker.class)).isSameAs(SubjectBanChecker.NONE);
            // Connecting is lazy: no Redis is needed to start.
            assertThat(ctx.getBean(RateLimitService.class)
                    .tryConsume("anything", LimitSubject.guest("192.0.2.1")).allowed()).isTrue();
        });
    }

    @Test
    void anApplicationClockAndRealBanCheckersReplaceTheDefaults() {
        runner.withUserConfiguration(Overrides.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(Clock.class);
            assertThat(ctx.getBean(Clock.class)).isInstanceOf(MutableClock.class);
            assertThat(ctx.getBean(RateLimitTimeMeter.class).currentTimeNanos())
                    .isEqualTo(Instant.parse("2026-09-27T12:00:00Z").getEpochSecond() * 1_000_000_000L);
            assertThat(ctx.getBean(IpBanChecker.class)).isNotSameAs(IpBanChecker.NONE);
            assertThat(ctx.getBean(SubjectBanChecker.class)).isNotSameAs(SubjectBanChecker.NONE);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Overrides {
        @Bean
        Clock testClock() {
            return new MutableClock(Instant.parse("2026-09-27T12:00:00Z"));
        }

        @Bean
        IpBanChecker realIpBans() {
            return raw -> Optional.of(Instant.MAX);
        }

        @Bean
        SubjectBanChecker realSubjectBans() {
            return sub -> Optional.empty();
        }
    }

    // --- helpers -------------------------------------------------------------------

    private record Bound(StandardEnvironment env, RateLimitProperties rl, QuotaProperties quota) {
    }

    private static Bound bind(Map<String, Object> envOverrides) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        // Keep the real OS environment out of it so the test is hermetic.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("env", envOverrides));
        env.getPropertySources().addLast(new ResourcePropertySource(new FileSystemResource(MAIN_PROPERTIES)));
        Binder binder = Binder.get(env);
        RateLimitProperties rl = binder.bind("sockbowl.ratelimit", RateLimitProperties.class)
                .orElseGet(RateLimitProperties::new);
        QuotaProperties quota = binder.bind("sockbowl.quota", QuotaProperties.class).orElseGet(QuotaProperties::new);
        return new Bound(env, rl, quota);
    }

    private static void assertPolicy(RateLimitProperties rl, String name, long capacity, long refillTokens,
                                     Duration period, KeyBy keyBy) {
        PolicySpec spec = rl.policy(name);
        assertThat(spec).as(name).isNotNull();
        assertThat(spec.getCapacity()).as(name + " capacity").isEqualTo(capacity);
        assertThat(spec.effectiveRefillTokens()).as(name + " refill tokens").isEqualTo(refillTokens);
        assertThat(spec.getRefillPeriod()).as(name + " refill period").isEqualTo(period);
        assertThat(spec.getKeyBy()).as(name + " key").isEqualTo(keyBy);
    }

}
