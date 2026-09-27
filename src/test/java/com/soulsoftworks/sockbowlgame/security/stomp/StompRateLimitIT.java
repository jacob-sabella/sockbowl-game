package com.soulsoftworks.sockbowlgame.security.stomp;

import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlgame.controller.helper.StompTestClient;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.ratelimit.ShippedLimitProperties;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STOMP throttling and transport limits end to end (plan m4-limits WP-G3;
 * M4-RL-05, M4-AB-02 STOMP half) with the shipped policies and auth off.
 * Time is a {@link MutableClock} so bucket refills are deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sockbowl.auth.enabled=false", "sockbowl.quota.enabled=false"})
@Import(StompRateLimitIT.ClockConfig.class)
class StompRateLimitIT extends StompSecurityITSupport {

    static final MutableClock CLOCK = MutableClock.startingNow();

    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry registry) {
        ShippedLimitProperties.registerRateLimits(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        Clock testClock() {
            return CLOCK;
        }
    }

    private GameSession game;
    private JoinGameResponse alice;

    @BeforeEach
    void freshBucketsAndSeat() {
        // Every test connects from 127.0.0.1: move past the per-IP ws-connect and
        // stomp-send-ip windows left by the previous test.
        CLOCK.advance(Duration.ofMinutes(5));
        game = newGame();
        alice = joinAsGuest(game, "Alice");
    }

    private StompTestClient.Connection connectAlice() throws Exception {
        StompTestClient.Connection c = connect(game.getId(), alice.getPlayerSessionId(), alice.getPlayerSecret(), null);
        c.awaitConnected();
        return c;
    }

    private String aliceQueue() {
        return "/queue/event/" + game.getId() + "/" + alice.getPlayerSessionId();
    }

    private static List<JsonObject> drain(BlockingQueue<String> queue, int atLeast) throws InterruptedException {
        List<JsonObject> out = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (out.size() < atLeast && System.nanoTime() < deadline) {
            String raw = queue.poll(200, TimeUnit.MILLISECONDS);
            if (raw != null) {
                out.add(json(raw));
            }
        }
        // Pick up anything still in flight.
        String raw;
        while ((raw = queue.poll(500, TimeUnit.MILLISECONDS)) != null) {
            out.add(json(raw));
        }
        return out;
    }

    @Test
    void buzzSpamIsDroppedWithANoticeAndRecoversAfterARefill() throws Exception {
        StompTestClient.Connection c = connectAlice();
        BlockingQueue<String> mine = c.subscribe(aliceQueue());
        BlockingQueue<String> errors = c.subscribe("/user/queue/errors");
        awaitSubscribed(aliceQueue(), 1);
        awaitSubscribed("/user/queue/errors", 1);

        for (int i = 0; i < 20; i++) {
            c.send(StompRateLimitGuard.BUZZ_DESTINATION, "{}");
        }

        // A spectator's buzz reaches the processor and is answered on the player
        // queue; only the stomp-buzz capacity (5) gets that far.
        List<JsonObject> replies = drain(mine, 5);
        assertThat(replies).hasSize(5);

        JsonObject notice = poll(errors);
        assertThat(notice.get("code").getAsString()).isEqualTo("RATE_LIMITED");
        assertThat(notice.get("policy").getAsString()).isEqualTo(StompRateLimitGuard.STOMP_BUZZ);
        assertThat(notice.get("droppedDestination").getAsString()).isEqualTo(StompRateLimitGuard.BUZZ_DESTINATION);
        assertThat(notice.get("retryAfterSeconds").getAsInt()).isEqualTo(1);
        assertThat(notice.get("retryAfterMs").getAsLong()).isBetween(1L, 1000L);
        // Throttled to one notice per connection per second.
        assertThat(errors.poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(c.isConnected()).as("soft rejections keep the socket open").isTrue();

        CLOCK.advance(Duration.ofSeconds(1));
        c.send(StompRateLimitGuard.BUZZ_DESTINATION, "{}");
        assertThat(poll(mine).has("messageContentType")).isTrue();
        assertThat(c.isConnected()).isTrue();
    }

    @Test
    void floodClosesTheSocketWithAStompFloodErrorFrame() throws Exception {
        StompTestClient.Connection c = connectAlice();
        for (int i = 0; i < 500 && c.isConnected(); i++) {
            try {
                c.send("/app/game/config/get-game", "{}");
            } catch (RuntimeException closed) {
                break;
            }
        }
        assertFatal(c, StompErrorCode.RATE_LIMITED);
        JsonObject body = json(c.awaitError().body());
        assertThat(body.get("policy").getAsString()).isEqualTo(StompRateLimitGuard.STOMP_FLOOD);
        assertThat(body.get("retryAfterSeconds").getAsInt()).isPositive();

        CLOCK.advance(Duration.ofSeconds(10));
        StompTestClient.Connection fresh = connectAlice();
        BlockingQueue<String> mine = fresh.subscribe(aliceQueue());
        awaitSubscribed(aliceQueue(), 1);
        JsonObject update = json(requestUntilReply(fresh, "/app/game/config/get-game", "{}", mine));
        assertThat(update.get("messageContentType").getAsString()).isEqualTo("GameSessionUpdate");
    }

    @Test
    void messagesUpToTheSizeLimitAreAccepted() throws Exception {
        StompTestClient.Connection c = connectAlice();
        BlockingQueue<String> mine = c.subscribe(aliceQueue());
        awaitSubscribed(aliceQueue(), 1);

        String body = "{\"padding\":\"" + "x".repeat(12 * 1024) + "\"}";
        JsonObject update = json(requestUntilReply(c, "/app/game/config/get-game", body, mine));
        assertThat(update.get("messageContentType").getAsString()).isEqualTo("GameSessionUpdate");
        assertThat(c.isConnected()).isTrue();
    }

    @Test
    void anOversizedMessageClosesTheSocket() throws Exception {
        StompTestClient.Connection c = connectAlice();
        String body = "{\"padding\":\"" + "x".repeat(20 * 1024) + "\"}";
        c.send("/app/game/config/get-game", body);
        assertThat(c.awaitClosed()).as("socket closed after a message over 16 KiB").isTrue();
    }

    @Test
    void connectFloodFromOneAddressIsRefused() throws Exception {
        // ws-connect: 10 per minute per IP. The previous tests' connects are in older windows.
        int refusedAt = -1;
        for (int i = 1; i <= 12 && refusedAt < 0; i++) {
            StompTestClient.Connection c = connect(game.getId(), alice.getPlayerSessionId(),
                    alice.getPlayerSecret(), null);
            try {
                c.awaitConnected();
                c.disconnect();
            } catch (Exception refused) {
                assertFatal(c, StompErrorCode.RATE_LIMITED);
                assertThat(json(c.awaitError().body()).get("policy").getAsString())
                        .isEqualTo(StompRateLimitGuard.WS_CONNECT);
                refusedAt = i;
            }
        }
        assertThat(refusedAt).isEqualTo(11);

        CLOCK.advance(Duration.ofMinutes(1));
        connectAlice();
    }
}
