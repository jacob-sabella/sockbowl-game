package com.soulsoftworks.sockbowlgame.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

@Configuration
@ConfigurationProperties("redis")
public class RedisConfig {

    private String hostname;
    private int port;
    private int database;
    private String password;
    private long timeout;

    @Bean
    JedisConnectionFactory jedisConnectionFactory() {
        return new JedisConnectionFactory();
    }
}
