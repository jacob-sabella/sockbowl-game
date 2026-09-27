package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.ratelimit.ShippedLimitProperties;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;

/**
 * M2's auth-off STOMP security suite re-run with the shipped M4 limits switched
 * on (plan m4-limits WP-G3): the pre-auth {@link StompRateLimitGuard} must not
 * change any M2 outcome for normal traffic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sockbowl.auth.enabled=false")
@Import(StompSecurityRateLimitedIT.ClockConfig.class)
class StompSecurityRateLimitedIT extends StompSecurityIT {

    static final MutableClock CLOCK = MutableClock.startingNow();

    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry registry) {
        ShippedLimitProperties.registerRateLimits(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        Clock testClock() {
            return CLOCK;
        }
    }

    /** All connections come from 127.0.0.1; start each test in a fresh ws-connect window. */
    @BeforeEach
    void freshLimiterWindow() {
        CLOCK.advance(Duration.ofMinutes(2));
    }
}
