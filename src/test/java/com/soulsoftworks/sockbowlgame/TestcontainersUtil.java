package com.soulsoftworks.sockbowlgame;

import com.redis.testcontainers.RedisContainer;
import org.testcontainers.utility.DockerImageName;

public class TestcontainersUtil {
    public static RedisContainer getRedisContainer(){
        return new RedisContainer(DockerImageName.parse("redislabs/redisearch:latest")).withExposedPorts(6379);
    }
}
