package com.soulsoftworks.sockbowlgame.config.health;

import com.soulsoftworks.sockbowlgame.service.MessageService;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * {@code DOWN} until the {@value MessageService#LISTENER_ID} Kafka listener
 * container has a non-empty partition assignment (plan m2-auth WP-G2 fix,
 * defect M2-LIVE-01).
 *
 * <p>{@code config.KafkaConfig} now sets {@code auto.offset.reset=earliest} so
 * a fresh consumer group doesn't skip messages produced before its first
 * assignment, but that alone doesn't make the first rebalance instantaneous:
 * between the container starting and {@code onPartitionsAssigned} firing (up
 * to tens of seconds observed on a freshly-started stack), a STOMP SEND is
 * still produced to Kafka successfully, and still has nobody consuming it
 * yet. Before this indicator, {@code GET /actuator/health} reported
 * {@code UP} the instant the web server came up, regardless of whether the
 * game-topic consumer was actually attached to any partition, so the compose
 * healthcheck (and anything gating on it, e.g. {@code smoke-auth.sh}) would
 * let traffic through during that window and see the SEND silently dropped:
 * no STOMP error frame, because nothing at the STOMP layer rejected
 * anything — the message simply landed on a topic nobody was reading yet.
 *
 * <h2>Health path / component name</h2>
 * This bean's default Spring name is {@code kafkaListenerReadinessHealthIndicator};
 * Spring Boot's health-contributor naming strips the {@code HealthIndicator}
 * suffix, so it contributes under the component name <b>{@code kafkaListenerReadiness}</b>:
 * <ul>
 *   <li>it is folded into the default aggregate at <b>{@code GET /actuator/health}</b>
 *       (already unauthenticated and compose-exposed; see {@code SecurityConfig}
 *       / {@code NoSecurityConfig} and the compose healthcheck's
 *       {@code HealthCheck.java}, which just checks the top-level status for
 *       {@code "UP"}) — so the existing healthcheck starts correctly reporting
 *       unhealthy during the rebalance window with no further changes needed;</li>
 *   <li>it is also included in the dedicated <b>{@code GET /actuator/health/readiness}</b>
 *       group (see {@code application.properties}:
 *       {@code management.endpoint.health.probes.enabled=true} and
 *       {@code management.endpoint.health.group.readiness.include=readinessState,kafkaListenerReadiness}),
 *       for a healthcheck (e.g. WP FIX-D1 in sockbowl-docker) that wants to
 *       probe readiness specifically, decoupled from liveness/other health
 *       facets. Like the main endpoint, the group reports only the top-level
 *       {@code status} ({@code management.endpoint.health.show-details=never}
 *       is the default carried through to groups too) — no component details
 *       leak on either path.</li>
 * </ul>
 */
@Component
public class KafkaListenerReadinessHealthIndicator implements HealthIndicator {

    private final KafkaListenerEndpointRegistry registry;

    public KafkaListenerReadinessHealthIndicator(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        MessageListenerContainer container = registry.getListenerContainer(MessageService.LISTENER_ID);
        if (container == null) {
            return Health.down()
                    .withDetail("reason", "listener container '" + MessageService.LISTENER_ID + "' is not registered")
                    .build();
        }
        if (!container.isRunning()) {
            return Health.down()
                    .withDetail("reason", "listener container '" + MessageService.LISTENER_ID + "' is not running")
                    .build();
        }
        Collection<TopicPartition> assigned = container.getAssignedPartitions();
        if (assigned == null || assigned.isEmpty()) {
            return Health.down()
                    .withDetail("reason", "no partitions assigned yet (rebalance in progress)")
                    .build();
        }
        return Health.up().withDetail("assignedPartitions", assigned.size()).build();
    }
}
