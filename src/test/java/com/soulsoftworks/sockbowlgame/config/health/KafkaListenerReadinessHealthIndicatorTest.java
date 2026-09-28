package com.soulsoftworks.sockbowlgame.config.health;

import com.soulsoftworks.sockbowlgame.service.MessageService;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link KafkaListenerReadinessHealthIndicator}
 * (plan m2-auth WP-G2 fix, M2-LIVE-01): it must report {@code DOWN} for every
 * state short of "running with an assigned partition", and never throw.
 */
class KafkaListenerReadinessHealthIndicatorTest {

    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final KafkaListenerReadinessHealthIndicator indicator =
            new KafkaListenerReadinessHealthIndicator(registry);

    @Test
    void downWhenListenerContainerIsNotRegistered() {
        when(registry.getListenerContainer(eq(MessageService.LISTENER_ID))).thenReturn(null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason",
                "listener container '" + MessageService.LISTENER_ID + "' is not registered");
    }

    @Test
    void downWhenListenerContainerIsNotRunning() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(eq(MessageService.LISTENER_ID))).thenReturn(container);
        when(container.isRunning()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason",
                "listener container '" + MessageService.LISTENER_ID + "' is not running");
    }

    @Test
    void downWhenRunningButNoPartitionsAssignedYet() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(eq(MessageService.LISTENER_ID))).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(container.getAssignedPartitions()).thenReturn(null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "no partitions assigned yet (rebalance in progress)");
    }

    @Test
    void downWhenRunningWithAnEmptyAssignment() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(eq(MessageService.LISTENER_ID))).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(container.getAssignedPartitions()).thenReturn(Set.of());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void upWhenRunningWithANonEmptyAssignment() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(eq(MessageService.LISTENER_ID))).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(container.getAssignedPartitions())
                .thenReturn(List.of(new TopicPartition("game-session-topic", 0)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("assignedPartitions", 1);
    }
}
