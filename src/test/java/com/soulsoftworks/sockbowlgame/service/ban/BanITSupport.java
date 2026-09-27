package com.soulsoftworks.sockbowlgame.service.ban;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.ratelimit.ClientIpResolver;
import com.soulsoftworks.sockbowlgame.ratelimit.IpBanChecker;
import com.soulsoftworks.sockbowlgame.ratelimit.IpBannedException;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitErrorResponses;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.repository.BanRepository;
import com.soulsoftworks.sockbowlgame.repository.IpBanRepository;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;

/**
 * Shared context for the WP-G4 ban ITs: the full application with
 * {@code sockbowl.auth.enabled=true}, real JPA against a Postgres
 * Testcontainer (the test properties normally switch JPA off), a Redis
 * Testcontainer for the mirrors, a {@link MutableClock} as the limits clock,
 * and a mocked {@link JwtDecoder} (callers use the {@code jwt()} post-processor).
 *
 * <p>The containers are started once per JVM and every subclass declares
 * nothing context-specific, so all of them share one cached context.
 *
 * <p>{@link IpBanProbeFilter} stands in for WP-G2's {@code RequestGuardFilter},
 * which runs in parallel on its own branch: it performs exactly the guard's
 * first step (the real {@link IpBanChecker} bean against
 * {@link ClientIpResolver#rawAddress}) and renders the 403 with
 * {@link LimitErrorResponses}, so these tests prove the bean the guard will
 * call without depending on G2's code.
 */
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        // Re-enable JPA (the shared test properties exclude it) against Postgres.
        "spring.autoconfigure.exclude=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/token",
        "spring.security.oauth2.client.registration.questions-svc.provider=keycloak",
        "spring.security.oauth2.client.registration.questions-svc.client-id=sockbowl-game-backend",
        "spring.security.oauth2.client.registration.questions-svc.client-secret=test-secret",
        "spring.security.oauth2.client.registration.questions-svc.authorization-grant-type=client_credentials",
        "sockbowl.questions.url=http://127.0.0.1:1/"
})
@AutoConfigureMockMvc
@Import(BanITSupport.BanItConfig.class)
abstract class BanITSupport {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("sockbowl_users");
    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    RateLimitRedis redis;
    @Autowired
    BanRepository banRepository;
    @Autowired
    IpBanRepository ipBanRepository;

    /** Clean slate in both stores, and the clock back at wall-clock time. */
    void resetStores() {
        clock.set(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        banRepository.deleteAll();
        ipBanRepository.deleteAll();
        redis.sync().flushdb();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BanItConfig {

        /** Replaces LimitsClockConfig's system clock (it backs off). */
        @Bean
        MutableClock limitsTestClock() {
            return MutableClock.startingNow();
        }

        @Bean
        FilterRegistrationBean<IpBanProbeFilter> ipBanProbeFilter(IpBanChecker checker, ClientIpResolver resolver) {
            FilterRegistrationBean<IpBanProbeFilter> registration =
                    new FilterRegistrationBean<>(new IpBanProbeFilter(checker, resolver));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    /** The request guard's IP-ban step (see the class comment). */
    static class IpBanProbeFilter extends OncePerRequestFilter {

        private final IpBanChecker checker;
        private final ClientIpResolver resolver;

        IpBanProbeFilter(IpBanChecker checker, ClientIpResolver resolver) {
            this.checker = checker;
            this.resolver = resolver;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            try {
                checker.ensureNotBanned(resolver.rawAddress(request));
            } catch (IpBannedException e) {
                LimitErrorResponses.write(response, e);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
