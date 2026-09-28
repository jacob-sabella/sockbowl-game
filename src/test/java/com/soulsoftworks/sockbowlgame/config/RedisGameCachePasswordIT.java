package com.soulsoftworks.sockbowlgame.config;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DK-3: {@code SOCKBOWL_REDIS_PASSWORD} wires into {@link RedisGameCacheConfig}'s
 * {@code JedisConnectionFactory} - the single connection factory bean that Redis
 * OM's {@code GameSession} repository (plan m4-limits usage, {@code
 * EnableRedisDocumentRepositories}) and {@code RedisEphemeralPacketBindings}
 * also share - against a Redis that actually enforces {@code requirepass}.
 * Proves AUTH is sent (not merely accepted as a no-op) when the password is
 * set, and that a missing or wrong password is refused rather than silently
 * treated as anonymous access.
 */
@Testcontainers
class RedisGameCachePasswordIT {

    private static final String PASSWORD = "dk3-s3cret-pw";

    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getAuthenticatedRedisContainer(PASSWORD);

    private static RedisGameCacheConfig configFor(String password) {
        RedisGameCacheConfig config = new RedisGameCacheConfig();
        config.setHostname(REDIS.getHost());
        config.setPort(REDIS.getMappedPort(6379));
        config.setDatabase(0);
        config.setPassword(password);
        return config;
    }

    private static JedisConnectionFactory connect(String password) {
        JedisConnectionFactory factory = configFor(password).jedisConnectionFactory();
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    @Test
    @DisplayName("SOCKBOWL_REDIS_PASSWORD set: the game-cache connection authenticates and can read/write")
    void authenticatesWhenPasswordIsSet() {
        JedisConnectionFactory factory = connect(PASSWORD);
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            redis.opsForValue().set("dk3:game-cache-auth-check", "ok");
            assertThat(redis.opsForValue().get("dk3:game-cache-auth-check")).isEqualTo("ok");
        } finally {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("No password configured against a requirepass server: every command is refused, not silently anonymous")
    void refusedWithoutAPassword() {
        JedisConnectionFactory factory = connect(null);
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            assertThatThrownBy(() -> redis.opsForValue().set("dk3:should-never-be-set", "no"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("NOAUTH");
        } finally {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("Wrong password against a requirepass server: refused, not silently accepted")
    void refusedWithWrongPassword() {
        JedisConnectionFactory factory = connect("not-the-configured-password");
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            assertThatThrownBy(() -> redis.opsForValue().set("dk3:should-never-be-set", "no"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("WRONGPASS");
        } finally {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("A blank password is treated exactly like no password: RedisPassword is never set on the standalone config")
    void blankPasswordIsTreatedAsUnset() {
        assertThat(configFor("").jedisConnectionFactory().getPassword()).isNull();
        assertThat(configFor("   ").jedisConnectionFactory().getPassword()).isNull();
        assertThat(configFor(null).jedisConnectionFactory().getPassword()).isNull();
        assertThat(configFor(PASSWORD).jedisConnectionFactory().getPassword()).isEqualTo(PASSWORD);
    }
}
