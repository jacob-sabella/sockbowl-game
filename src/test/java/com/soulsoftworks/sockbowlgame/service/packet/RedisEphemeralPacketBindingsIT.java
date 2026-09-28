package com.soulsoftworks.sockbowlgame.service.packet;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The EPHEMERAL packet binding against a real Redis (R3-G-01). */
@Testcontainers
class RedisEphemeralPacketBindingsIT {

    @Container
    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    static JedisConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory = new JedisConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @Test
    @DisplayName("The first game binds the packet; it may bind again, every other game is refused")
    void firstGameWins() {
        RedisEphemeralPacketBindings bindings = new RedisEphemeralPacketBindings(factory, Duration.ofHours(48));
        String packet = "pkt-" + UUID.randomUUID();

        assertThat(bindings.bindToGame(packet, "game-A")).isTrue();
        assertThat(bindings.bindToGame(packet, "game-A")).isTrue();
        assertThat(bindings.bindToGame(packet, "game-B")).isFalse();
        assertThat(redis.opsForValue().get(RedisEphemeralPacketBindings.KEY_PREFIX + packet)).isEqualTo("game-A");
    }

    @Test
    @DisplayName("The binding carries the configured TTL, at least the questions ephemeral TTL")
    void bindingExpires() {
        RedisEphemeralPacketBindings bindings = new RedisEphemeralPacketBindings(factory, Duration.ofHours(48));
        String packet = "pkt-" + UUID.randomUUID();
        bindings.bindToGame(packet, "game-A");

        Long ttlSeconds = redis.getExpire(RedisEphemeralPacketBindings.KEY_PREFIX + packet);
        assertThat(ttlSeconds).isBetween(Duration.ofHours(24).toSeconds(), Duration.ofHours(48).toSeconds());
    }

    @Test
    @DisplayName("Once the binding expires, a new game may bind the packet")
    void expiredBindingIsFree() throws Exception {
        RedisEphemeralPacketBindings bindings = new RedisEphemeralPacketBindings(redis, Duration.ofSeconds(1));
        String packet = "pkt-" + UUID.randomUUID();
        assertThat(bindings.bindToGame(packet, "game-A")).isTrue();
        Thread.sleep(1500);
        assertThat(bindings.bindToGame(packet, "game-B")).isTrue();
        assertThat(bindings.bindToGame(packet, "game-A")).isFalse();
    }

    @Test
    @DisplayName("Games racing to bind the same packet: exactly one wins (SET NX)")
    void concurrentBindsHaveOneWinner() throws Exception {
        RedisEphemeralPacketBindings bindings = new RedisEphemeralPacketBindings(factory, Duration.ofHours(48));
        String packet = "pkt-" + UUID.randomUUID();
        int games = 16;
        ExecutorService pool = Executors.newFixedThreadPool(games);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < games; i++) {
                String game = "game-" + i;
                Callable<Boolean> bind = () -> {
                    go.await();
                    return bindings.bindToGame(packet, game);
                };
                results.add(pool.submit(bind));
            }
            go.countDown();
            int winners = 0;
            for (Future<Boolean> r : results) {
                if (r.get()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Blank ids never bind, and a non-positive TTL is a configuration error")
    void rejectsBadInput() {
        RedisEphemeralPacketBindings bindings = new RedisEphemeralPacketBindings(factory, Duration.ofHours(48));
        assertThat(bindings.bindToGame("", "game-A")).isFalse();
        assertThat(bindings.bindToGame("pkt", null)).isFalse();
        assertThatThrownBy(() -> new RedisEphemeralPacketBindings(factory, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
