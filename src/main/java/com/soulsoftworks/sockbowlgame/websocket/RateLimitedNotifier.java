package com.soulsoftworks.sockbowlgame.websocket;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.soulsoftworks.sockbowlgame.config.WebSocketLimitsProperties;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.StompError;
import com.soulsoftworks.sockbowlgame.ratelimit.Decision;
import com.soulsoftworks.sockbowlgame.security.stomp.StompErrorCode;
import com.soulsoftworks.sockbowlgame.security.stomp.StompPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * Tells a connection that one of its frames was dropped by the STOMP limiter
 * (plan m4-limits sections 2.1 and 2.5): a {@link StompError} with code
 * {@code RATE_LIMITED}, {@code retryAfterSeconds}/{@code retryAfterMs},
 * {@code policy} and {@code droppedDestination}, sent to the principal on
 * {@code /user/queue/errors} (M2's non-fatal error channel; every connection,
 * guests included, has a {@link StompPrincipal}).
 *
 * <p>The notices are themselves limited to one per connection per
 * {@code sockbowl.websocket.rate-limited-notice-interval} (1s), so a flood
 * cannot turn into an outbound flood. Local only; no Redis.
 */
@Slf4j
@Component
public class RateLimitedNotifier {

    public static final String ERRORS_DESTINATION = "/queue/errors";

    private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;
    private final Cache<String, Boolean> recentlyNotified;

    /**
     * The template is looked up lazily: it is created by the message-broker
     * configuration, which itself depends on the inbound interceptor this
     * notifier is part of.
     */
    public RateLimitedNotifier(ObjectProvider<SimpMessagingTemplate> messagingTemplate,
                               WebSocketLimitsProperties properties,
                               Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.recentlyNotified = Caffeine.newBuilder()
                .expireAfterWrite(properties.getRateLimitedNoticeInterval())
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    /**
     * Sends a {@code RATE_LIMITED} notice unless this connection got one within
     * the notice interval.
     *
     * @return true when a notice was sent
     */
    public boolean notifyDropped(StompPrincipal principal, String connectionId, String policy,
                                 Decision decision, String droppedDestination) {
        if (principal == null || connectionId == null) {
            return false;
        }
        if (recentlyNotified.asMap().putIfAbsent(connectionId, Boolean.TRUE) != null) {
            return false;
        }
        SimpMessagingTemplate template = messagingTemplate.getIfAvailable();
        if (template == null) {
            return false;
        }
        try {
            template.convertAndSendToUser(principal.getName(), ERRORS_DESTINATION,
                    notice(policy, decision, droppedDestination));
            return true;
        } catch (RuntimeException e) {
            log.debug("Could not send a RATE_LIMITED notice to {}: {}", principal, e.toString());
            return false;
        }
    }

    /** Forgets a closed connection's throttle state. */
    public void forget(String connectionId) {
        if (connectionId != null) {
            recentlyNotified.invalidate(connectionId);
        }
    }

    static StompError notice(String policy, Decision decision, String droppedDestination) {
        // Rounded up, so a client that waits exactly this long finds a token.
        long retryAfterMs = Math.max(1, (decision.retryAfterNanos() + 999_999) / 1_000_000);
        return StompError.builder()
                .code(StompErrorCode.RATE_LIMITED.name())
                .message("Too many messages; slow down")
                .retryAfterSeconds((int) Math.min(Integer.MAX_VALUE, decision.retryAfterSeconds()))
                .retryAfterMs(retryAfterMs)
                .policy(policy)
                .droppedDestination(droppedDestination)
                .build();
    }
}
