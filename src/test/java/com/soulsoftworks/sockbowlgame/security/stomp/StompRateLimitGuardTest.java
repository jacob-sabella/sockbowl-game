package com.soulsoftworks.sockbowlgame.security.stomp;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.ratelimit.BucketConfigurations;
import com.soulsoftworks.sockbowlgame.ratelimit.Decision;
import com.soulsoftworks.sockbowlgame.ratelimit.IpBanChecker;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlgame.ratelimit.LocalBucketRegistry;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitEventRecorder;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitProperties;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitService;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitTimeMeter;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import com.soulsoftworks.sockbowlgame.websocket.ClientIpHandshakeInterceptor;
import com.soulsoftworks.sockbowlgame.websocket.RateLimitedNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-G3 acceptance (plan m4-limits sections 2.5 and 4): the pre-auth STOMP
 * guard with the <b>shipped</b> policies, real in-memory buckets, a real
 * Redis ({@code ws-connect}) and a {@link MutableClock}, so every trigger is
 * followed by a recovery without sleeping.
 */
@Testcontainers
class StompRateLimitGuardTest {

    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    private static final String BUZZ = StompRateLimitGuard.BUZZ_DESTINATION;
    private static final String GET_GAME = "/app/game/config/get-game";

    private MutableClock clock;
    private RateLimitProperties properties;
    private RateLimitRedis redis;
    private LocalBucketRegistry buckets;
    private RateLimitedNotifier notifier;
    private RateLimitEventRecorder recorder;
    private final AtomicReference<Optional<Instant>> ipBan = new AtomicReference<>(Optional.empty());
    private final AtomicReference<String> lastBanLookup = new AtomicReference<>();
    private StompRateLimitGuard guard;

    private final StompPrincipal guest = StompPrincipal.guest("g1", "p1");
    private final AtomicInteger ipSeq = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        clock = MutableClock.startingNow();
        properties = shippedProperties();
        RateLimitTimeMeter timeMeter = new RateLimitTimeMeter(clock);
        redis = new RateLimitRedis(REDIS.getHost(), REDIS.getMappedPort(6379), 0, null,
                properties.getRedis(), timeMeter);
        redis.sync().flushdb();
        BucketConfigurations configurations = new BucketConfigurations(properties);
        RateLimitService service = new RateLimitService(properties, redis, configurations, clock);
        buckets = new LocalBucketRegistry(properties, configurations, timeMeter);
        notifier = mock(RateLimitedNotifier.class);
        when(notifier.notifyDropped(any(), anyString(), anyString(), any(), any())).thenReturn(true);
        recorder = mock(RateLimitEventRecorder.class);
        IpBanChecker checker = raw -> {
            lastBanLookup.set(raw);
            return ipBan.get();
        };
        guard = new StompRateLimitGuard(service, buckets, checker, notifier, recorder, properties, clock);
    }

    @AfterEach
    void tearDown() {
        redis.destroy();
    }

    /** The shipped {@code application.properties}, bound the way Boot binds it (placeholders at their defaults). */
    static RateLimitProperties shippedProperties() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new ResourcePropertySource(
                new FileSystemResource("src/main/resources/application.properties")));
        return Binder.get(env).bind("sockbowl.ratelimit", RateLimitProperties.class)
                .orElseThrow(() -> new IllegalStateException("no sockbowl.ratelimit.* keys"));
    }

    private String nextIp() {
        return "198.51.100." + ipSeq.incrementAndGet();
    }

    private static StompHeaderAccessor frame(StompCommand command, String connectionId, String ip,
                                             String destination, String... nativeHeaders) {
        StompHeaderAccessor accessor = StompTestFrames.frame(command, destination, nativeHeaders);
        accessor.setSessionId(connectionId);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ClientIpHandshakeInterceptor.CLIENT_IP, ip);
        attributes.put(ClientIpHandshakeInterceptor.CLIENT_IP_RAW, ip);
        accessor.setSessionAttributes(attributes);
        return accessor;
    }

    private StompGuardResult send(String connectionId, String ip, String destination, String... headers) {
        return guard.check(frame(StompCommand.SEND, connectionId, ip, destination, headers), guest);
    }

    /** A SUBSCRIBE carrying its own distinct {@code id} header, as a real STOMP client sends. */
    private StompGuardResult subscribe(String connectionId, String ip, String id) {
        return guard.check(frame(StompCommand.SUBSCRIBE, connectionId, ip, "/queue/event/" + id, "id", id), guest);
    }

    /** An UNSUBSCRIBE carrying an explicit {@code id} header. */
    private StompGuardResult unsubscribe(String connectionId, String ip, String id) {
        return guard.check(frame(StompCommand.UNSUBSCRIBE, connectionId, ip, null, "id", id), guest);
    }

    private void connect(String connectionId, String ip) {
        guard.check(frame(StompCommand.CONNECT, connectionId, ip, null), null);
    }

    private static StompRejectedException rejection(Runnable call) {
        try {
            call.run();
        } catch (StompRejectedException e) {
            return e;
        }
        throw new AssertionError("expected a StompRejectedException");
    }

    @Test
    void runsPreAuthAtOrderMinus100() {
        assertThat(guard.order()).isEqualTo(-100).isNegative();
    }

    /* -------------------------------- buzz --------------------------------- */

    @Test
    void sixthBuzzInABurstIsDroppedAndTheNotifierIsCalledOnce() {
        String ip = nextIp();
        for (int i = 1; i <= 5; i++) {
            assertThat(send("c1", ip, BUZZ)).as("buzz %d", i).isEqualTo(StompGuardResult.PASS);
        }
        assertThat(send("c1", ip, BUZZ)).as("buzz 6").isEqualTo(StompGuardResult.DROP);

        verify(notifier, times(1)).notifyDropped(eq(guest), eq("c1"), eq(StompRateLimitGuard.STOMP_BUZZ),
                any(Decision.class), eq(BUZZ));
        verify(recorder, times(1)).record(eq(StompRateLimitGuard.STOMP_BUZZ),
                eq(RateLimitEventRecorder.KIND_RATE), any(LimitSubject.class), eq(BUZZ));
        // Buzzes do not starve ordinary game messages on the same connection.
        assertThat(send("c1", ip, GET_GAME)).isEqualTo(StompGuardResult.PASS);
    }

    @Test
    void droppedBuzzCarriesTheWaitUntilTheNextToken() {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            send("c1", ip, BUZZ);
        }
        send("c1", ip, BUZZ);
        var captor = org.mockito.ArgumentCaptor.forClass(Decision.class);
        verify(notifier).notifyDropped(any(), anyString(), anyString(), captor.capture(), any());
        // 3 tokens per second: the next one is at most 1/3 s away.
        assertThat(captor.getValue().retryAfterNanos()).isPositive()
                .isLessThanOrEqualTo(Duration.ofMillis(334).toNanos());
        assertThat(captor.getValue().retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void buzzRecoversAfterOneSecond() {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            send("c1", ip, BUZZ);
        }
        assertThat(send("c1", ip, BUZZ)).isEqualTo(StompGuardResult.DROP);

        clock.advance(Duration.ofSeconds(1));
        assertThat(send("c1", ip, BUZZ)).as("recovered after 1s").isEqualTo(StompGuardResult.PASS);
        assertThat(send("c1", ip, BUZZ)).isEqualTo(StompGuardResult.PASS);
        assertThat(send("c1", ip, BUZZ)).isEqualTo(StompGuardResult.PASS);
        assertThat(send("c1", ip, BUZZ)).as("3 per second").isEqualTo(StompGuardResult.DROP);
    }

    @Test
    void rotatingThePlayerSessionIdHeaderDoesNotResetTheBuckets() {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            assertThat(send("c1", ip, BUZZ, "playerSessionId", "p-" + i, "gameSessionId", "g-" + i))
                    .isEqualTo(StompGuardResult.PASS);
        }
        assertThat(send("c1", ip, BUZZ, "playerSessionId", "p-fresh", "gameSessionId", "g-fresh"))
                .as("buckets are keyed by the STOMP session id, not by headers")
                .isEqualTo(StompGuardResult.DROP);
    }

    @Test
    void connectionsHaveIndependentBuckets() {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            send("c1", ip, BUZZ);
        }
        assertThat(send("c1", ip, BUZZ)).isEqualTo(StompGuardResult.DROP);
        assertThat(send("c2", ip, BUZZ)).as("another connection").isEqualTo(StompGuardResult.PASS);
    }

    /* ------------------------------- flood --------------------------------- */

    @Test
    void onceStompFloodIsEmptyTheNextRejectionIsFatal() {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            send("c1", ip, BUZZ);
        }
        // stomp-flood: 50 soft rejections per 10s are tolerated.
        for (int i = 1; i <= 50; i++) {
            assertThat(send("c1", ip, BUZZ)).as("soft rejection %d", i).isEqualTo(StompGuardResult.DROP);
        }
        StompRejectedException hard = rejection(() -> send("c1", ip, BUZZ));
        assertThat(hard.getCode()).isEqualTo(StompErrorCode.RATE_LIMITED);
        assertThat(hard.getPolicy()).isEqualTo(StompRateLimitGuard.STOMP_FLOOD);
        assertThat(hard.getRetryAfterSeconds()).isPositive();
        verify(recorder).record(eq(StompRateLimitGuard.STOMP_FLOOD), eq(RateLimitEventRecorder.KIND_RATE),
                any(LimitSubject.class), eq(BUZZ));

        // A fresh connection after the flood window is served normally.
        clock.advance(Duration.ofSeconds(10));
        assertThat(send("c2", ip, BUZZ)).isEqualTo(StompGuardResult.PASS);
    }

    /* ------------------------------- send ---------------------------------- */

    @Test
    void sendBurstOf40PerConnectionThenRecoversAt20PerSecond() {
        String ip = nextIp();
        for (int i = 1; i <= 40; i++) {
            assertThat(send("c1", ip, GET_GAME)).as("send %d", i).isEqualTo(StompGuardResult.PASS);
        }
        assertThat(send("c1", ip, GET_GAME)).isEqualTo(StompGuardResult.DROP);
        verify(notifier).notifyDropped(eq(guest), eq("c1"), eq(StompRateLimitGuard.STOMP_SEND), any(), eq(GET_GAME));

        clock.advance(Duration.ofSeconds(1));
        for (int i = 1; i <= 20; i++) {
            assertThat(send("c1", ip, GET_GAME)).as("refilled send %d", i).isEqualTo(StompGuardResult.PASS);
        }
        assertThat(send("c1", ip, GET_GAME)).isEqualTo(StompGuardResult.DROP);
    }

    @Test
    void sendIpBucketIsSharedByConnectionsFromOneAddress() {
        String ip = nextIp();
        for (int c = 1; c <= 3; c++) {
            for (int i = 0; i < 40; i++) {
                assertThat(send("c" + c, ip, GET_GAME)).isEqualTo(StompGuardResult.PASS);
            }
        }
        // 120 from this address: a fourth, fresh connection is limited per IP.
        assertThat(send("c4", ip, GET_GAME)).isEqualTo(StompGuardResult.DROP);
        verify(notifier).notifyDropped(eq(guest), eq("c4"), eq(StompRateLimitGuard.STOMP_SEND_IP), any(), any());
        assertThat(send("c5", nextIp(), GET_GAME)).as("another address").isEqualTo(StompGuardResult.PASS);

        clock.advance(Duration.ofSeconds(1));
        assertThat(send("c4", ip, GET_GAME)).as("recovered").isEqualTo(StompGuardResult.PASS);
    }

    @Test
    void signedInTierScalesTheConnectionBuckets() {
        StompPrincipal author = StompPrincipal.user("g1", "p2", "kc-author", Set.of("author", "player"), null);
        String ip = nextIp();
        for (int i = 1; i <= 10; i++) {
            assertThat(guard.check(frame(StompCommand.SEND, "c1", ip, BUZZ), author)).as("buzz %d", i)
                    .isEqualTo(StompGuardResult.PASS);
        }
        assertThat(guard.check(frame(StompCommand.SEND, "c1", ip, BUZZ), author)).isEqualTo(StompGuardResult.DROP);
    }

    /* ---------------------------- disconnect ------------------------------- */

    @Test
    void disconnectFrameEvictsTheConnectionsBuckets() {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            send("c1", ip, BUZZ);
        }
        assertThat(send("c1", ip, BUZZ)).isEqualTo(StompGuardResult.DROP);

        assertThat(guard.check(frame(StompCommand.DISCONNECT, "c1", ip, null), guest))
                .isEqualTo(StompGuardResult.PASS);
        verify(notifier).forget("c1");
        assertThat(send("c1", ip, BUZZ)).as("buckets were evicted").isEqualTo(StompGuardResult.PASS);
    }

    @Test
    void sessionDisconnectEventEvictsTheConnectionsBuckets() {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            send("c1", ip, BUZZ);
        }
        long before = buckets.size();
        assertThat(before).isPositive();

        guard.onDisconnect(new SessionDisconnectEvent(this,
                org.springframework.messaging.support.MessageBuilder.withPayload(new byte[0]).build(),
                "c1", CloseStatus.NORMAL));
        assertThat(send("c1", ip, BUZZ)).isEqualTo(StompGuardResult.PASS);
    }

    /* ------------------------------ connect -------------------------------- */

    @Test
    void eleventhConnectFromOneAddressWithinAMinuteIsRateLimitedThenRecovers() {
        String ip = nextIp();
        for (int i = 1; i <= 10; i++) {
            connect("c" + i, ip);
        }
        StompRejectedException limited = rejection(() -> connect("c11", ip));
        assertThat(limited.getCode()).isEqualTo(StompErrorCode.RATE_LIMITED);
        assertThat(limited.getPolicy()).isEqualTo(StompRateLimitGuard.WS_CONNECT);
        // 10 per minute, refilled greedily: one every 6s.
        assertThat(limited.getRetryAfterSeconds()).isEqualTo(6);
        verify(recorder).record(eq(StompRateLimitGuard.WS_CONNECT), eq(RateLimitEventRecorder.KIND_RATE),
                any(LimitSubject.class), anyString());

        connect("c12", nextIp()); // another address is not affected

        clock.advance(Duration.ofMinutes(1));
        for (int i = 13; i <= 22; i++) {
            connect("c" + i, ip);
        }
    }

    @Test
    void ipBannedConnectIsRejected() {
        String ip = nextIp();
        ipBan.set(Optional.of(clock.instant().plus(Duration.ofHours(1))));

        StompRejectedException banned = rejection(() -> connect("c1", ip));
        assertThat(banned.getCode()).isEqualTo(StompErrorCode.IP_BANNED);
        assertThat(banned.getRetryAfterSeconds()).isEqualTo(3600);
        assertThat(lastBanLookup.get()).isEqualTo(ip);
        verify(recorder).record(eq("ip-ban"), eq(RateLimitEventRecorder.KIND_BAN), any(LimitSubject.class),
                anyString());

        // Lifting (or expiry of) the ban restores access.
        ipBan.set(Optional.empty());
        connect("c2", ip);
    }

    @Test
    void ipBanIsMatchedOnTheRawAddressNotTheLimiterKey() {
        StompHeaderAccessor accessor = frame(StompCommand.CONNECT, "c1", "2001:db8:1:2::/64", null);
        accessor.getSessionAttributes().put(ClientIpHandshakeInterceptor.CLIENT_IP_RAW, "2001:db8:1:2::abcd");
        guard.check(accessor, null);
        assertThat(lastBanLookup.get()).isEqualTo("2001:db8:1:2::abcd");
    }

    /* ------------------------------ disabled ------------------------------- */

    @Test
    void disabledLimiterOnlyChecksIpBans() {
        properties.setEnabled(false);
        String ip = nextIp();
        for (int i = 0; i < 200; i++) {
            assertThat(send("c1", ip, BUZZ)).isEqualTo(StompGuardResult.PASS);
        }
        for (int i = 0; i < 20; i++) {
            connect("c" + i, ip);
        }
        verify(notifier, never()).notifyDropped(any(), anyString(), anyString(), any(), any());

        ipBan.set(Optional.of(clock.instant().plus(Duration.ofMinutes(5))));
        assertThat(rejection(() -> connect("c99", ip)).getCode()).isEqualTo(StompErrorCode.IP_BANNED);
    }

    /**
     * G-M4-V1-03: before this fix, only SEND was charged, so a SUBSCRIBE (or
     * UNSUBSCRIBE/ACK/NACK/BEGIN/COMMIT/ABORT) flood was completely free -
     * unlike this test's old name and assertion claimed.
     */
    @Test
    void nonSendCommandsShareTheSameConnectionSendBucketAsSend() {
        String ip = nextIp();
        // ACK carries no destination and has no subscription cap of its own,
        // so it can safely exhaust the whole 40-token stomp-send bucket alone.
        for (int i = 1; i <= 40; i++) {
            assertThat(guard.check(frame(StompCommand.ACK, "c1", ip, null), guest)).as("ack %d", i)
                    .isEqualTo(StompGuardResult.PASS);
        }
        assertThat(send("c1", ip, GET_GAME)).as("the bucket is shared with SEND").isEqualTo(StompGuardResult.DROP);

        clock.advance(Duration.ofSeconds(1));
        assertThat(guard.check(frame(StompCommand.NACK, "c1", ip, null), guest)).isEqualTo(StompGuardResult.PASS);
    }

    @Test
    void heartbeatsAreNeverChargedOrCapped() {
        String ip = nextIp();
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        heartbeat.setSessionId("c1");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ClientIpHandshakeInterceptor.CLIENT_IP, ip);
        attributes.put(ClientIpHandshakeInterceptor.CLIENT_IP_RAW, ip);
        heartbeat.setSessionAttributes(attributes);
        for (int i = 0; i < 100; i++) {
            assertThat(guard.check(heartbeat, guest)).isEqualTo(StompGuardResult.PASS);
        }
        // The stomp-send bucket (40) is still full.
        for (int i = 1; i <= 40; i++) {
            assertThat(send("c1", ip, GET_GAME)).as("send %d", i).isEqualTo(StompGuardResult.PASS);
        }
    }

    /* --------------------------- subscription cap --------------------------- */

    @Test
    void subscribesUpToTheDefaultCapOfSixteenAllPass() {
        String ip = nextIp();
        for (int i = 1; i <= 16; i++) {
            assertThat(subscribe("c1", ip, "s" + i)).as("subscribe %d", i).isEqualTo(StompGuardResult.PASS);
        }
    }

    @Test
    void seventeenthSubscribeOnOneConnectionIsRejectedFatally() {
        String ip = nextIp();
        for (int i = 1; i <= 16; i++) {
            subscribe("c1", ip, "s" + i);
        }
        StompRejectedException rejected = rejection(() -> subscribe("c1", ip, "s17"));
        assertThat(rejected.getCode()).isEqualTo(StompErrorCode.RATE_LIMITED);
        assertThat(rejected.getPolicy()).isEqualTo(StompRateLimitGuard.STOMP_SUBSCRIPTIONS);
        verify(recorder).record(eq(StompRateLimitGuard.STOMP_SUBSCRIPTIONS), eq(RateLimitEventRecorder.KIND_RATE),
                any(LimitSubject.class), eq("/queue/event/s17"));

        // Another connection is unaffected.
        assertThat(subscribe("c2", ip, "s1")).isEqualTo(StompGuardResult.PASS);
    }

    /**
     * G-M4-FIX3-01: rewritten from the old {@code unsubscribeFreesASlotUnderTheCap},
     * which sent an UNSUBSCRIBE with no {@code id} header and only happened to
     * pass because the guard used to decrement a bare counter on ANY
     * UNSUBSCRIBE. It now unsubscribes one of the connection's real ids.
     */
    @Test
    void unsubscribeFreesASlotUnderTheCap() {
        String ip = nextIp();
        for (int i = 1; i <= 16; i++) {
            subscribe("c1", ip, "s" + i);
        }
        assertThat(unsubscribe("c1", ip, "s1")).isEqualTo(StompGuardResult.PASS);
        assertThat(subscribe("c1", ip, "s17")).as("a freed slot admits one more")
                .isEqualTo(StompGuardResult.PASS);
    }

    /**
     * G-M4-FIX3-01 (the blocker): before this fix, an UNSUBSCRIBE with no id or
     * an id never subscribed on this connection still freed a slot, so a client
     * at the cap could keep sending bogus UNSUBSCRIBEs and re-subscribing
     * forever.
     */
    @Test
    void unsubscribeWithNoIdOrAnUnknownIdFreesNoSlot() {
        String ip = nextIp();
        for (int i = 1; i <= 16; i++) {
            subscribe("c1", ip, "s" + i);
        }

        assertThat(guard.check(frame(StompCommand.UNSUBSCRIBE, "c1", ip, null), guest))
                .as("no id header at all").isEqualTo(StompGuardResult.PASS);
        assertThat(unsubscribe("c1", ip, "never-subscribed")).as("an id never subscribed on this connection")
                .isEqualTo(StompGuardResult.PASS);
        assertThat(unsubscribe("c1", ip, "s1")).as("unsubscribing s1 elsewhere doesn't matter here")
                .isEqualTo(StompGuardResult.PASS);
        // s1 was the only real id freed above; two bogus UNSUBSCRIBEs freed nothing.
        assertThat(subscribe("c1", ip, "s17")).as("only one slot was actually freed")
                .isEqualTo(StompGuardResult.PASS);
        StompRejectedException rejected = rejection(() -> subscribe("c1", ip, "s18"));
        assertThat(rejected.getPolicy()).isEqualTo(StompRateLimitGuard.STOMP_SUBSCRIPTIONS);
    }

    /**
     * G-M4-FIX3-01 (the exact bypass described in the plan): 16 subscriptions,
     * then a flood of bogus UNSUBSCRIBEs, then a 17th SUBSCRIBE is still
     * refused. Before this fix, every one of those bogus UNSUBSCRIBEs would
     * have freed a slot, letting the client subscribe again forever.
     */
    @Test
    void sixteenSubscriptionsThenBogusUnsubscribesStillRejectsTheSeventeenth() {
        String ip = nextIp();
        for (int i = 1; i <= 16; i++) {
            subscribe("c1", ip, "s" + i);
        }
        // Clock advances keep every bogus UNSUBSCRIBE within the stomp-send
        // budget, so it actually reaches the subscription bookkeeping instead
        // of being send-rate-dropped (which would prove nothing here).
        for (int batch = 0; batch < 5; batch++) {
            clock.advance(Duration.ofSeconds(2));
            for (int i = 0; i < 5; i++) {
                assertThat(guard.check(frame(StompCommand.UNSUBSCRIBE, "c1", ip, null), guest))
                        .as("bogus (no id) unsubscribe").isEqualTo(StompGuardResult.PASS);
                assertThat(unsubscribe("c1", ip, "bogus-" + batch + "-" + i)).as("bogus (unknown id) unsubscribe")
                        .isEqualTo(StompGuardResult.PASS);
            }
        }
        clock.advance(Duration.ofSeconds(2));
        StompRejectedException rejected = rejection(() -> subscribe("c1", ip, "s17"));
        assertThat(rejected.getCode()).isEqualTo(StompErrorCode.RATE_LIMITED);
        assertThat(rejected.getPolicy()).isEqualTo(StompRateLimitGuard.STOMP_SUBSCRIPTIONS);
    }

    /** A SUBSCRIBE that repeats an id already live on the connection does not grow the set (documented choice). */
    @Test
    void resubscribingTheSameIdDoesNotGrowTheSet() {
        String ip = nextIp();
        for (int i = 1; i <= 16; i++) {
            subscribe("c1", ip, "s" + i);
        }
        assertThat(subscribe("c1", ip, "s1")).as("s1 is already live; this is a no-op for the cap")
                .isEqualTo(StompGuardResult.PASS);
        // s1 was never re-added, so the set is still exactly 16 and a real new id is refused.
        StompRejectedException rejected = rejection(() -> subscribe("c1", ip, "s17"));
        assertThat(rejected.getPolicy()).isEqualTo(StompRateLimitGuard.STOMP_SUBSCRIPTIONS);
    }

    @Test
    void disconnectResetsTheSubscriptionCount() {
        String ip = nextIp();
        for (int i = 1; i <= 16; i++) {
            subscribe("c1", ip, "s" + i);
        }
        guard.check(frame(StompCommand.DISCONNECT, "c1", ip, null), guest);
        for (int i = 1; i <= 16; i++) {
            assertThat(subscribe("c1", ip, "s" + i)).as("reconnect %d", i).isEqualTo(StompGuardResult.PASS);
        }
    }

    @Test
    void subscriptionCapIsSkippedWhenRateLimitingIsDisabled() {
        properties.setEnabled(false);
        String ip = nextIp();
        for (int i = 1; i <= 30; i++) {
            assertThat(subscribe("c1", ip, "s" + i)).as("subscribe %d", i).isEqualTo(StompGuardResult.PASS);
        }
    }
}
