package com.soulsoftworks.sockbowlgame.ratelimit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.ProctorType;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WP-G2 with {@code sockbowl.auth.enabled=false}: {@code NoSecurityConfig}
 * carries the same request guard, every caller is a guest keyed by IP, and the
 * guest {@code session-create} limit trips on the fourth create and recovers
 * after the ten-minute refill.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sockbowl.auth.enabled=false",
        "sockbowl.quota.enabled=false"
})
@AutoConfigureMockMvc
@Import(RequestGuardFilterAuthOffIT.ClockConfig.class)
class RequestGuardFilterAuthOffIT {

    private static final String CREATE = "/api/v1/session/create-new-game-session";
    private static final MutableClock CLOCK = MutableClock.startingNow();

    @Container
    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
        ShippedLimitProperties.registerRateLimits(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FilterChainProxy filterChainProxy;

    private final Gson gson = new Gson();

    private MvcResult create(String ip) throws Exception {
        return mvc.perform(post(CREATE)
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(CreateGameRequest.builder()
                                .gameSettings(GameSettings.builder()
                                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                                        .proctorType(ProctorType.ONLINE_PROCTOR)
                                        .build())
                                .build())))
                .andReturn();
    }

    @Test
    void guardIsInTheAuthOffChainBeforeAuthorization() {
        List<Filter> filters = filterChainProxy.getFilterChains().get(0).getFilters();
        int guard = RequestGuardFilterIT.indexOf(filters, RequestGuardFilter.class);
        int authorization = RequestGuardFilterIT.indexOf(filters, AuthorizationFilter.class);
        assertThat(guard).isNotNegative().isLessThan(authorization);
    }

    @Test
    void sessionCreateTripsOnTheFourthAndRecoversAfterTenMinutes() throws Exception {
        String ip = "10.77.0.1";
        for (int i = 1; i <= 3; i++) {
            assertThat(create(ip).getResponse().getStatus()).as("create #%d", i).isEqualTo(200);
        }

        MvcResult limited = create(ip);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo("session-create");
        assertThat(limited.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_LIMIT)).isEqualTo("3");
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
        JsonObject body = gson.fromJson(limited.getResponse().getContentAsString(), JsonObject.class);
        assertThat(body.get("error").getAsString()).isEqualTo("rate_limited");
        assertThat(body.get("policy").getAsString()).isEqualTo("session-create");

        assertThat(create("10.77.0.2").getResponse().getStatus()).isEqualTo(200);

        CLOCK.advance(Duration.ofMinutes(10));
        assertThat(create(ip).getResponse().getStatus()).isEqualTo(200);
    }
}
