package com.soulsoftworks.sockbowlgame.service.packet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@link EphemeralPacketBindings} in the game-cache Redis: one key per bound
 * packet, {@code sockbowl:ephemeral-packet-game:<packetId>} holding the game
 * session id, written with SET NX so two games racing to load the same packet
 * cannot both win.
 *
 * <p>The key lives for {@code sockbowl.packet.ephemeral-binding-ttl} (default
 * 48h). It must be at least sockbowl-questions' {@code sockbowl.packet.ephemeral-ttl}
 * (default 24h), after which the packet itself is deleted, so a binding never
 * lapses while the packet can still be loaded.
 */
@Component
public class RedisEphemeralPacketBindings implements EphemeralPacketBindings {

    static final String KEY_PREFIX = "sockbowl:ephemeral-packet-game:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    @Autowired
    public RedisEphemeralPacketBindings(RedisConnectionFactory connectionFactory,
                                        @Value("${sockbowl.packet.ephemeral-binding-ttl:48h}") Duration ttl) {
        this(new StringRedisTemplate(connectionFactory), ttl);
    }

    RedisEphemeralPacketBindings(StringRedisTemplate redis, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("sockbowl.packet.ephemeral-binding-ttl must be positive");
        }
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public boolean bindToGame(String packetId, String gameSessionId) {
        if (packetId == null || packetId.isBlank() || gameSessionId == null || gameSessionId.isBlank()) {
            return false;
        }
        String key = KEY_PREFIX + packetId;
        // Two tries: the key can expire between a failed SET NX and the GET.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, gameSessionId, ttl))) {
                return true;
            }
            String holder = redis.opsForValue().get(key);
            if (holder != null) {
                return holder.equals(gameSessionId);
            }
        }
        return false;
    }
}
