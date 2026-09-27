package com.soulsoftworks.sockbowlgame.ratelimit;

import com.soulsoftworks.sockbowlgame.quota.QuotaProperties;
import com.soulsoftworks.sockbowlgame.quota.QuotaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the limiter core (plan m4-limits section 2.2): the limiter's own lazy
 * Lettuce connection to the game Redis (host/port/db from
 * {@code sockbowl.redis.game-cache.*}; Redis OM's Jedis factory is untouched),
 * the clock-backed time meter, and the services built on them.
 */
@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, QuotaProperties.class})
public class RateLimitRedisConfig {

    @Bean
    RateLimitTimeMeter rateLimitTimeMeter(Clock clock) {
        return new RateLimitTimeMeter(clock);
    }

    @Bean
    RateLimitRedis rateLimitRedis(
            @Value("${sockbowl.redis.game-cache.hostname:localhost}") String host,
            @Value("${sockbowl.redis.game-cache.port:6379}") int port,
            @Value("${sockbowl.redis.game-cache.database:0}") int database,
            @Value("${sockbowl.redis.game-cache.password:}") String password,
            RateLimitProperties properties,
            RateLimitTimeMeter timeMeter) {
        return new RateLimitRedis(host, port, database, password, properties.getRedis(), timeMeter);
    }

    @Bean
    BucketConfigurations bucketConfigurations(RateLimitProperties properties) {
        return new BucketConfigurations(properties);
    }

    @Bean
    RateLimitService rateLimitService(RateLimitProperties properties, RateLimitRedis redis,
                                      BucketConfigurations configurations, Clock clock) {
        return new RateLimitService(properties, redis, configurations, clock);
    }

    @Bean
    LocalBucketRegistry localBucketRegistry(RateLimitProperties properties, BucketConfigurations configurations,
                                            RateLimitTimeMeter timeMeter) {
        return new LocalBucketRegistry(properties, configurations, timeMeter);
    }

    @Bean
    RateLimitEventRecorder rateLimitEventRecorder(RateLimitRedis redis, RateLimitProperties properties,
                                                  Clock clock) {
        return new RateLimitEventRecorder(redis, properties, clock);
    }

    @Bean
    QuotaService quotaService(QuotaProperties properties, RateLimitRedis redis, Clock clock) {
        return new QuotaService(properties, redis, clock);
    }
}
