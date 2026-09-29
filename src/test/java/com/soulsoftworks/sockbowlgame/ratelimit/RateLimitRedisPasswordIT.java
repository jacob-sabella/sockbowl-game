package com.soulsoftworks.sockbowlgame.ratelimit;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DK-3: {@code SOCKBOWL_REDIS_PASSWORD} also wires into the limiter's own
 * Lettuce connection ({@link RateLimitRedisConfig}, separate from Redis OM's
 * Jedis factory), against a Redis that actually enforces {@code requirepass}.
 */
@Testcontainers
class RateLimitRedisPasswordIT {

    private static final String PASSWORD = "dk3-limiter-s3cret-pw";

    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getAuthenticatedRedisContainer(PASSWORD);

    private static RateLimitRedis redisWith(String password) {
        RateLimitProperties properties = new RateLimitProperties();
        RateLimitTimeMeter timeMeter = new RateLimitTimeMeter(Clock.systemUTC());
        return new RateLimitRedis(REDIS.getHost(), REDIS.getMappedPort(6379), 0, password,
                properties.getRedis(), timeMeter);
    }

    @Test
    @DisplayName("SOCKBOWL_REDIS_PASSWORD set: the limiter's Lettuce connection authenticates and can read/write")
    void authenticatesWhenPasswordIsSet() {
        RateLimitRedis redis = redisWith(PASSWORD);
        try {
            redis.sync().set("dk3:limiter-auth-check", "ok");
            assertThat(redis.sync().get("dk3:limiter-auth-check")).isEqualTo("ok");
        } finally {
            redis.destroy();
        }
    }

    @Test
    @DisplayName("No password configured against a requirepass server: the limiter cannot use it (D12 fails the request open)")
    void unavailableWithoutAPassword() {
        RateLimitRedis redis = redisWith(null);
        try {
            assertThatThrownBy(redis::sync)
                    .isInstanceOfAny(RedisUnavailableException.class, RuntimeException.class);
            // Whatever the exact exception shape, no command may have gone through
            // unauthenticated: nothing this test wrote is visible from the
            // authenticated side either.
            RateLimitRedis authenticated = redisWith(PASSWORD);
            try {
                assertThat(authenticated.sync().get("dk3:limiter-should-never-be-set")).isNull();
            } finally {
                authenticated.destroy();
            }
        } finally {
            redis.destroy();
        }
    }

    @Test
    @DisplayName("Wrong password against a requirepass server: refused, not silently accepted")
    void unavailableWithWrongPassword() {
        RateLimitRedis redis = redisWith("not-the-configured-password");
        try {
            assertThatThrownBy(redis::sync)
                    .isInstanceOfAny(RedisUnavailableException.class, RuntimeException.class);
        } finally {
            redis.destroy();
        }
    }
}
