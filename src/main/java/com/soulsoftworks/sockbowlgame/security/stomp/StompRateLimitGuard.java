package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.ratelimit.Decision;
import com.soulsoftworks.sockbowlgame.ratelimit.IpBanChecker;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlgame.ratelimit.LocalBucketRegistry;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitEventRecorder;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitProperties;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitService;
import com.soulsoftworks.sockbowlgame.ratelimit.Tier;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.websocket.ClientIpHandshakeInterceptor;
import com.soulsoftworks.sockbowlgame.websocket.RateLimitedNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STOMP inbound throttling (plan m4-limits section 2.5, WP-G3; M4-RL-05, and the
 * STOMP half of M4-AB-02). A <b>pre-auth</b> {@link StompInboundGuard}
 * ({@link #ORDER} {@code = -100}) inside M2's single inbound interceptor, so a
 * CONNECT flood is refused before any JWT is decoded.
 *
 * <ul>
 *   <li><b>CONNECT</b>: the client address (from the handshake attribute
 *       {@value ClientIpHandshakeInterceptor#CLIENT_IP_RAW}) must not be inside
 *       an IP/CIDR ban ({@code IP_BANNED}), then the Redis {@code ws-connect}
 *       policy is charged per IP ({@code RATE_LIMITED}, policy
 *       {@code ws-connect}). Both are fatal: ERROR frame, socket closed.</li>
 *   <li><b>Every non-CONNECT, non-DISCONNECT, non-heartbeat frame</b> (SEND,
 *       SUBSCRIBE, UNSUBSCRIBE, ACK, NACK, BEGIN, COMMIT, ABORT) charges
 *       {@code stomp-send} (per connection) and {@code stomp-send-ip} (per
 *       address, per instance); a SEND to {@value #BUZZ_DESTINATION} also
 *       charges {@code stomp-buzz} (per connection). G-M4-V1-03: this used to
 *       be SEND-only, so a SUBSCRIBE flood was free. All three are in-memory
 *       buckets ({@link LocalBucketRegistry}) keyed by the STOMP session id,
 *       never by client-supplied headers such as {@code playerSessionId}, so
 *       no Redis round trip is made per message. A rejected frame is
 *       <b>dropped</b> ({@link StompGuardResult#DROP}; the socket stays open),
 *       the connection gets a throttled {@code StompError{RATE_LIMITED}} on
 *       {@code /user/queue/errors} ({@link RateLimitedNotifier}), and
 *       {@code stomp-flood} is charged. When {@code stomp-flood} is empty the
 *       rejection turns fatal: {@code RATE_LIMITED} ERROR frame (policy
 *       {@code stomp-flood}) and the socket is closed.</li>
 *   <li><b>SUBSCRIBE</b> also has a hard, non-token-bucket cap
 *       ({@code sockbowl.ratelimit.stomp.max-subscriptions-per-connection},
 *       default 16) on live subscriptions per connection (G-M4-V1-03): past
 *       it, the frame is refused fatally ({@code RATE_LIMITED}) rather than
 *       merely dropped, since letting a connection accumulate unbounded
 *       broker subscriptions is itself the amplification risk, independent of
 *       how fast they arrived. UNSUBSCRIBE decrements the count.</li>
 *   <li><b>DISCONNECT</b> (and {@link SessionDisconnectEvent}): the
 *       connection's local buckets and subscription count are dropped.</li>
 * </ul>
 *
 * <p>With {@code sockbowl.ratelimit.enabled=false} only the IP-ban check runs
 * (the buckets report "unlimited"), matching the REST request guard.
 */
@Slf4j
@Component
public class StompRateLimitGuard implements StompInboundGuard {

    public static final int ORDER = -100;

    public static final String WS_CONNECT = "ws-connect";
    public static final String STOMP_SEND = "stomp-send";
    public static final String STOMP_SEND_IP = "stomp-send-ip";
    public static final String STOMP_BUZZ = "stomp-buzz";
    public static final String STOMP_FLOOD = "stomp-flood";
    public static final String STOMP_SUBSCRIPTIONS = "stomp-subscriptions";

    public static final String BUZZ_DESTINATION = "/app/game/player-incoming-buzz";

    /**
     * Commands (other than CONNECT/STOMP, DISCONNECT and heartbeats, which
     * {@link #check} handles separately) that charge {@code stomp-send} /
     * {@code stomp-send-ip} (G-M4-V1-03).
     */
    private static final Set<StompCommand> CHARGED_COMMANDS = Set.of(StompCommand.SEND, StompCommand.SUBSCRIBE,
            StompCommand.UNSUBSCRIBE, StompCommand.ACK, StompCommand.NACK, StompCommand.BEGIN,
            StompCommand.COMMIT, StompCommand.ABORT);

    private final RateLimitService rateLimitService;
    private final LocalBucketRegistry localBuckets;
    private final IpBanChecker ipBanChecker;
    private final RateLimitedNotifier notifier;
    private final RateLimitEventRecorder eventRecorder;
    private final RateLimitProperties properties;
    private final Clock clock;

    /** Live SUBSCRIBE count per connection key ({@link UsageKeys#connectionPart}); see {@link #STOMP_SUBSCRIPTIONS}. */
    private final ConcurrentMap<String, AtomicInteger> subscriptionCounts = new ConcurrentHashMap<>();

    public StompRateLimitGuard(RateLimitService rateLimitService,
                               LocalBucketRegistry localBuckets,
                               IpBanChecker ipBanChecker,
                               RateLimitedNotifier notifier,
                               RateLimitEventRecorder eventRecorder,
                               RateLimitProperties properties,
                               Clock clock) {
        this.rateLimitService = rateLimitService;
        this.localBuckets = localBuckets;
        this.ipBanChecker = ipBanChecker;
        this.notifier = notifier;
        this.eventRecorder = eventRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public StompGuardResult check(StompHeaderAccessor accessor, StompPrincipal principal) {
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            checkConnect(accessor);
            return StompGuardResult.PASS;
        }
        if (command == StompCommand.DISCONNECT
                || (command == null && accessor.getMessageType() == SimpMessageType.DISCONNECT)) {
            evict(accessor.getSessionId());
            return StompGuardResult.PASS;
        }
        if (command != null && CHARGED_COMMANDS.contains(command)) {
            return checkFrame(accessor, principal);
        }
        // Heartbeats (command null) and anything else unrecognized: nothing to charge.
        return StompGuardResult.PASS;
    }

    /** Drops the closed connection's local buckets (also covers sockets that die without a DISCONNECT frame). */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        evict(event.getSessionId());
    }

    private void checkConnect(StompHeaderAccessor accessor) {
        String rawIp = ClientIpHandshakeInterceptor.rawClientIp(accessor.getSessionAttributes());
        String ip = ClientIpHandshakeInterceptor.clientIp(accessor.getSessionAttributes());
        LimitSubject subject = LimitSubject.guest(ip);

        Optional<Instant> ban = ipBanChecker.findActiveBan(rawIp);
        if (ban.isPresent()) {
            eventRecorder.record("ip-ban", RateLimitEventRecorder.KIND_BAN, subject, "STOMP CONNECT");
            throw new StompRejectedException(StompErrorCode.IP_BANNED,
                    "Connections from your network address are banned", secondsUntil(ban.get()));
        }

        Decision decision = rateLimitService.tryConsume(WS_CONNECT, subject);
        if (!decision.allowed()) {
            eventRecorder.record(WS_CONNECT, RateLimitEventRecorder.KIND_RATE, subject, "STOMP CONNECT");
            throw new StompRejectedException(StompErrorCode.RATE_LIMITED,
                    "Too many connection attempts; try again later", retryAfter(decision), WS_CONNECT);
        }
    }

    private StompGuardResult checkFrame(StompHeaderAccessor accessor, StompPrincipal principal) {
        String connectionId = accessor.getSessionId();
        if (connectionId == null) {
            return StompGuardResult.PASS;
        }
        String ip = ClientIpHandshakeInterceptor.clientIp(accessor.getSessionAttributes());
        String connectionKey = UsageKeys.connectionPart(connectionId);
        Tier tier = tierOf(principal);
        StompCommand command = accessor.getCommand();
        String destination = accessor.getDestination();

        String rejectedPolicy = null;
        Decision rejected = charge(connectionKey, STOMP_SEND, tier);
        if (rejected != null) {
            rejectedPolicy = STOMP_SEND;
        } else {
            // Per-address buckets are shared by every connection from that address,
            // whatever their tier, so they always use the base (guest) scaling.
            rejected = charge(UsageKeys.ipPart(ip), STOMP_SEND_IP, Tier.GUEST);
            if (rejected != null) {
                rejectedPolicy = STOMP_SEND_IP;
            } else if (command == StompCommand.SEND && BUZZ_DESTINATION.equals(destination)) {
                rejected = charge(connectionKey, STOMP_BUZZ, tier);
                if (rejected != null) {
                    rejectedPolicy = STOMP_BUZZ;
                }
            }
        }
        if (rejected == null) {
            if (command == StompCommand.SUBSCRIBE) {
                return checkSubscriptionCap(accessor, principal, connectionKey, ip, tier);
            }
            if (command == StompCommand.UNSUBSCRIBE) {
                decrementSubscriptionCount(connectionKey);
            }
            return StompGuardResult.PASS;
        }

        LimitSubject subject = new LimitSubject(principal == null ? null : principal.getKeycloakId(), ip, tier);
        Decision flood = localBuckets.tryConsume(connectionKey, STOMP_FLOOD, tier);
        if (!flood.allowed()) {
            eventRecorder.record(STOMP_FLOOD, RateLimitEventRecorder.KIND_RATE, subject, destination);
            log.info("Closing STOMP connection {} ({}) for flooding", connectionId, principal);
            throw new StompRejectedException(StompErrorCode.RATE_LIMITED,
                    "Too many messages; the connection is closed", retryAfter(flood), STOMP_FLOOD);
        }
        if (notifier.notifyDropped(principal, connectionId, rejectedPolicy, rejected, destination)) {
            // Sampled like the notice itself: at most one event per connection per second.
            eventRecorder.record(rejectedPolicy, RateLimitEventRecorder.KIND_RATE, subject, destination);
        }
        log.debug("Dropped STOMP {} to {} on {} ({})", command, destination, connectionId, rejectedPolicy);
        return StompGuardResult.DROP;
    }

    /**
     * G-M4-V1-03: a hard cap on live subscriptions per connection, separate
     * from (and checked after) the token-bucket charge above, so a burst that
     * stays under the bucket capacities can't still accumulate unbounded
     * broker subscriptions. Past the cap the SUBSCRIBE is refused fatally
     * (the socket closes) rather than dropped, since - unlike a SEND - there
     * is no useful "try again in a moment" for it: the caller is already at
     * as many subscriptions as it's ever allowed to hold at once.
     */
    private StompGuardResult checkSubscriptionCap(StompHeaderAccessor accessor, StompPrincipal principal,
                                                   String connectionKey, String ip, Tier tier) {
        if (!properties.isEnabled()) {
            return StompGuardResult.PASS;
        }
        int max = properties.getStomp().getMaxSubscriptionsPerConnection();
        int count = subscriptionCounts.computeIfAbsent(connectionKey, k -> new AtomicInteger()).incrementAndGet();
        if (count > max) {
            subscriptionCounts.get(connectionKey).decrementAndGet();
            LimitSubject subject = new LimitSubject(principal == null ? null : principal.getKeycloakId(), ip, tier);
            eventRecorder.record(STOMP_SUBSCRIPTIONS, RateLimitEventRecorder.KIND_RATE, subject,
                    accessor.getDestination());
            log.info("Closing STOMP connection {} ({}) for exceeding {} subscriptions",
                    accessor.getSessionId(), principal, max);
            throw new StompRejectedException(StompErrorCode.RATE_LIMITED,
                    "Too many active subscriptions on this connection", null, STOMP_SUBSCRIPTIONS);
        }
        return StompGuardResult.PASS;
    }

    private void decrementSubscriptionCount(String connectionKey) {
        subscriptionCounts.computeIfPresent(connectionKey, (k, count) -> {
            int updated = count.updateAndGet(v -> Math.max(0, v - 1));
            return updated == 0 ? null : count;
        });
    }

    /** @return the rejecting decision, or null when a token was taken */
    private Decision charge(String localKey, String policy, Tier tier) {
        Decision decision = localBuckets.tryConsume(localKey, policy, tier);
        return decision.allowed() ? null : decision;
    }

    private void evict(String connectionId) {
        if (connectionId != null) {
            String connectionKey = UsageKeys.connectionPart(connectionId);
            localBuckets.invalidate(connectionKey);
            subscriptionCounts.remove(connectionKey);
            notifier.forget(connectionId);
        }
    }

    private static Tier tierOf(StompPrincipal principal) {
        if (principal == null || principal.isGuest() || principal.getKeycloakId() == null) {
            return Tier.GUEST;
        }
        return LimitSubjectResolver.tierOf(principal.getAuthorities());
    }

    private static Integer retryAfter(Decision decision) {
        return (int) Math.min(Integer.MAX_VALUE, decision.retryAfterSeconds());
    }

    private Integer secondsUntil(Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        long seconds = Duration.between(clock.instant(), expiresAt).toSeconds();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
    }
}
