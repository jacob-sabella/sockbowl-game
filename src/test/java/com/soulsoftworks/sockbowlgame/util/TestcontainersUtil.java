package com.soulsoftworks.sockbowlgame.util;

import com.redis.testcontainers.RedisContainer;
import org.testcontainers.utility.DockerImageName;

public class TestcontainersUtil {
    private static final String REDIS_IMAGE = "redis:8.2";

    public static RedisContainer getRedisContainer(){
        // Match the redis:8.2 image compose ships (Redis Open Source 8 bundles
        // Search/JSON natively, so the legacy redislabs/redisearch image is no
        // longer needed).
        return new RedisContainer(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
    }

    /** DK-3: a Redis requiring AUTH, for proving SOCKBOWL_REDIS_PASSWORD is actually used. */
    public static RedisContainer getAuthenticatedRedisContainer(String password){
        return new RedisContainer(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", password);
    }
}
