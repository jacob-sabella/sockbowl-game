package com.soulsoftworks.sockbowlgame.websocket;

import com.soulsoftworks.sockbowlgame.config.WebSocketLimitsProperties;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.StompError;
import com.soulsoftworks.sockbowlgame.ratelimit.Decision;
import com.soulsoftworks.sockbowlgame.security.stomp.StompPrincipal;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The soft-rejection notice (plan m4-limits section 2.1): an M2
 * {@link StompError} with code {@code RATE_LIMITED} to the principal on
 * {@code /user/queue/errors}, at most once per connection per second.
 */
class RateLimitedNotifierTest {

    private final StompPrincipal alice = StompPrincipal.guest("g1", "p1");
    private SimpMessagingTemplate template;
    private MutableClock clock;
    private RateLimitedNotifier notifier;

    @BeforeEach
    void setUp() {
        template = mock(SimpMessagingTemplate.class);
        clock = new MutableClock(Instant.parse("2026-09-27T12:00:00Z"));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("template", template);
        notifier = new RateLimitedNotifier(beans.getBeanProvider(SimpMessagingTemplate.class),
                new WebSocketLimitsProperties(), clock);
    }

    private static Decision rejected(Duration wait) {
        return Decision.rejected(wait.toNanos(), 5);
    }

    @Test
    void sendsARateLimitedStompErrorToThePrincipal() {
        assertThat(notifier.notifyDropped(alice, "c1", "stomp-buzz", rejected(Duration.ofMillis(250)),
                "/app/game/player-incoming-buzz")).isTrue();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(eq("g1:p1"), eq("/queue/errors"), payload.capture());
        StompError error = (StompError) payload.getValue();
        assertThat(error.getMessageType()).isEqualTo("StompError");
        assertThat(error.getCode()).isEqualTo("RATE_LIMITED");
        assertThat(error.getPolicy()).isEqualTo("stomp-buzz");
        assertThat(error.getRetryAfterMs()).isEqualTo(250);
        assertThat(error.getRetryAfterSeconds()).isEqualTo(1);
        assertThat(error.getDroppedDestination()).isEqualTo("/app/game/player-incoming-buzz");
        assertThat(error.getMessage()).isNotBlank();
    }

    @Test
    void atMostOneNoticePerConnectionPerSecond() {
        for (int i = 0; i < 20; i++) {
            notifier.notifyDropped(alice, "c1", "stomp-send", rejected(Duration.ofMillis(50)), "/app/x");
        }
        verify(template, times(1)).convertAndSendToUser(anyString(), anyString(), any(Object.class));

        // A different connection has its own allowance.
        notifier.notifyDropped(StompPrincipal.guest("g1", "p2"), "c2", "stomp-send",
                rejected(Duration.ofMillis(50)), "/app/x");
        verify(template, times(2)).convertAndSendToUser(anyString(), anyString(), any(Object.class));

        clock.advance(Duration.ofMillis(999));
        assertThat(notifier.notifyDropped(alice, "c1", "stomp-send", rejected(Duration.ofMillis(50)), "/app/x"))
                .isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(notifier.notifyDropped(alice, "c1", "stomp-send", rejected(Duration.ofMillis(50)), "/app/x"))
                .isTrue();
    }

    @Test
    void forgetResetsTheThrottle() {
        notifier.notifyDropped(alice, "c1", "stomp-send", rejected(Duration.ofMillis(50)), "/app/x");
        notifier.forget("c1");
        assertThat(notifier.notifyDropped(alice, "c1", "stomp-send", rejected(Duration.ofMillis(50)), "/app/x"))
                .isTrue();
    }

    @Test
    void noPrincipalMeansNoNotice() {
        assertThat(notifier.notifyDropped(null, "c1", "stomp-send", rejected(Duration.ofMillis(50)), "/app/x"))
                .isFalse();
        verify(template, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    }

    @Test
    void aFailedSendNeverPropagates() {
        doThrow(new IllegalStateException("broker down")).when(template)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));
        assertThat(notifier.notifyDropped(alice, "c1", "stomp-send", rejected(Duration.ofMillis(50)), "/app/x"))
                .isFalse();
    }
}
