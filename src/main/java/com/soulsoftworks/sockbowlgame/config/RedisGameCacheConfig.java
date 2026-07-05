package com.soulsoftworks.sockbowlgame.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "sockbowl.redis.game-cache")
@Data
public class RedisGameCacheConfig {

    private String hostname;
    private int port;
    private int database;
    private String password;
    private long timeout;

    @Bean
    JedisConnectionFactory jedisConnectionFactory() {
        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(hostname, port);
        standaloneConfig.setDatabase(database);
        if (password != null && !password.isBlank()) {
            standaloneConfig.setPassword(RedisPassword.of(password));
        }

        JedisClientConfiguration.JedisClientConfigurationBuilder clientConfig = JedisClientConfiguration.builder();
        if (timeout > 0) {
            clientConfig.readTimeout(Duration.ofMillis(timeout))
                    .connectTimeout(Duration.ofMillis(timeout));
        }

        return new JedisConnectionFactory(standaloneConfig, clientConfig.build());
    }
}
