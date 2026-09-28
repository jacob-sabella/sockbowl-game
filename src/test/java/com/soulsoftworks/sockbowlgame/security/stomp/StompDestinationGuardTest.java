package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.soulsoftworks.sockbowlgame.security.stomp.StompConnectAuthenticatorTest.provider;
import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.frame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEND/SUBSCRIBE rules for connected sockets (plan m2-auth WP-G2, section 2.5;
 * AUTH-01, AUTH-02, AUTH-12).
 */
class StompDestinationGuardTest {

    private static final String GAME = "g1";
    private static final String ME = "p1";

    private TestJwtIssuer issuer;
    private final StompPrincipal guest = StompPrincipal.guest(GAME, ME);

    @BeforeEach
    void setUp() throws Exception {
        issuer = new TestJwtIssuer();
    }

    @AfterEach
    void tearDown() throws Exception {
        issuer.close();
    }

    private StompDestinationGuard guard(boolean authEnabled) {
        return guard(authEnabled, Clock.systemUTC());
    }

    private StompDestinationGuard guard(boolean authEnabled, Clock clock) {
        return new StompDestinationGuard(new GameAuthorizationPolicy(authEnabled, null),
                provider(authEnabled ? issuer.decoder() : null), clock);
    }

    private StompPrincipal alice(Instant expiresAt) {
        return StompPrincipal.user(GAME, ME, "kc-alice", Set.of("game:host"), expiresAt);
    }

    private static void assertRejected(Runnable call, StompErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(StompRejectedException.class)
                .extracting(e -> ((StompRejectedException) e).getCode())
                .isEqualTo(code);
    }

    private static StompHeaderAccessor send(String destination, String... headers) {
        return frame(StompCommand.SEND, destination, headers);
    }

    private static StompHeaderAccessor subscribe(String destination) {
        return frame(StompCommand.SUBSCRIBE, destination);
    }

    @Test
    void runsPostAuthAtOrder100() {
        assertThat(guard(false).order()).isEqualTo(100);
    }

    /* -------------------------------- SEND -------------------------------- */

    @ParameterizedTest
    @ValueSource(strings = {"/queue/event/g1", "/queue/event/g1/p2", "/user/x", "/user/p2/queue/errors",
            "/topic/x", "/queue/heartbeat", "/app", "app/game/x", "/apps/game", "/app/../queue/event/g1",
            // G-M4-V1-02: AntPathMatcher-based @MessageMapping routing ignores
            // empty path segments, so these still reached the buzz handler
            // before this canonicalization check existed.
            "/app/game//player-incoming-buzz", "/app//game/player-incoming-buzz",
            "/app/game/./player-incoming-buzz", "/app/game/player-incoming-buzz/",
            "/app/game/player-incoming-buzz/.", "/app/game\\player-incoming-buzz",
            "/app/game/%2e%2e/player-incoming-buzz"})
    void sendOutsideAppIsForbidden(String destination) {
        assertRejected(() -> guard(false).check(send(destination), guest), StompErrorCode.FORBIDDEN_DESTINATION);
        assertRejected(() -> guard(true).check(send(destination), alice(Instant.now().plusSeconds(60))),
                StompErrorCode.FORBIDDEN_DESTINATION);
    }

    @Test
    void sendWithNoDestinationIsForbidden() {
        assertRejected(() -> guard(false).check(send(null), guest), StompErrorCode.FORBIDDEN_DESTINATION);
    }

    @Test
    void sendToAppIsAllowed() {
        assertThat(guard(false).check(send("/app/game/config/get-game"), guest)).isEqualTo(StompGuardResult.PASS);
        assertThat(guard(false).check(send("/app/heartbeat"), guest)).isEqualTo(StompGuardResult.PASS);
        assertThat(guard(true).check(send("/app/game/buzz"), alice(Instant.now().plusSeconds(60))))
                .isEqualTo(StompGuardResult.PASS);
    }

    @Test
    void matchingIdentityHeadersAreAllowed() {
        assertThat(guard(false).check(send("/app/game/x", "gameSessionId", GAME, "playerSessionId", ME), guest))
                .isEqualTo(StompGuardResult.PASS);
    }

    @Test
    void mismatchedIdentityHeadersAreIdentityMismatch() {
        assertRejected(() -> guard(false).check(send("/app/game/x", "gameSessionId", "g2"), guest),
                StompErrorCode.IDENTITY_MISMATCH);
        assertRejected(() -> guard(false).check(send("/app/game/x", "playerSessionId", "p2"), guest),
                StompErrorCode.IDENTITY_MISMATCH);
        assertRejected(() -> guard(true).check(send("/app/game/x", "gameSessionId", GAME, "playerSessionId", "p2"),
                alice(Instant.now().plusSeconds(60))), StompErrorCode.IDENTITY_MISMATCH);
    }

    @Test
    void sendWithoutPrincipalIsAuthRequired() {
        assertRejected(() -> guard(false).check(send("/app/game/x"), null), StompErrorCode.AUTH_REQUIRED);
        assertRejected(() -> guard(true).check(send("/queue/event/g1"), null), StompErrorCode.AUTH_REQUIRED);
    }

    @Test
    void authorizationOnSendRefreshesExpiryAndAuthorities() {
        Instant almostExpired = Instant.now().plusSeconds(5);
        StompPrincipal p = alice(almostExpired);
        String fresh = issuer.user("kc-alice", "game:host", "packet:create");

        assertThat(guard(true).check(send("/app/game/x", "Authorization", "Bearer " + fresh), p))
                .isEqualTo(StompGuardResult.PASS);

        assertThat(p.getTokenExpiresAt()).isAfter(almostExpired.plusSeconds(200));
        assertThat(p.getAuthorities()).containsExactlyInAnyOrder("game:host", "packet:create");
    }

    @Test
    void refreshedTokenLetsAnExpiredPrincipalSendAgain() {
        StompPrincipal p = alice(Instant.now().minusSeconds(10));
        assertRejected(() -> guard(true).check(send("/app/game/x"), p), StompErrorCode.TOKEN_EXPIRED);

        guard(true).check(send("/app/game/x", "Authorization", "Bearer " + issuer.user("kc-alice")), p);
        assertThat(guard(true).check(send("/app/game/x"), p)).isEqualTo(StompGuardResult.PASS);
    }

    @Test
    void expiredWithoutRefreshIsTokenExpired() {
        Instant expiry = Instant.parse("2026-01-01T00:00:00Z");
        Clock after = Clock.fixed(expiry.plusSeconds(1), ZoneOffset.UTC);
        Clock before = Clock.fixed(expiry.minusSeconds(1), ZoneOffset.UTC);
        assertThat(guard(true, before).check(send("/app/game/x"), alice(expiry))).isEqualTo(StompGuardResult.PASS);
        assertRejected(() -> guard(true, after).check(send("/app/game/x"), alice(expiry)),
                StompErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void refreshWithAnotherUsersTokenIsIdentityMismatch() {
        StompPrincipal p = alice(Instant.now().plusSeconds(60));
        assertRejected(() -> guard(true).check(send("/app/game/x", "Authorization",
                "Bearer " + issuer.user("kc-bob")), p), StompErrorCode.IDENTITY_MISMATCH);
    }

    @Test
    void refreshWithExpiredOrBadTokenIsRejected() {
        StompPrincipal p = alice(Instant.now().plusSeconds(60));
        String expired = issuer.token("kc-alice", "sockbowl-game", List.of(TestJwtIssuer.AUDIENCE),
                Instant.now().minusSeconds(3600));
        assertRejected(() -> guard(true).check(send("/app/game/x", "Authorization", "Bearer " + expired), p),
                StompErrorCode.TOKEN_EXPIRED);
        assertRejected(() -> guard(true).check(send("/app/game/x", "Authorization", "Bearer junk"), p),
                StompErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void guestsAndAuthOffIgnoreAuthorizationOnSend() {
        assertThat(guard(true).check(send("/app/game/x", "Authorization", "Bearer junk"), guest))
                .isEqualTo(StompGuardResult.PASS);
        assertThat(guard(false).check(send("/app/game/x", "Authorization", "Bearer junk"), guest))
                .isEqualTo(StompGuardResult.PASS);
    }

    /* ------------------------------ SUBSCRIBE ----------------------------- */

    @ParameterizedTest
    @ValueSource(strings = {"/queue/event/g1", "/queue/event/g1/p1", "/user/queue/errors", "/queue/heartbeat"})
    void subscribeToOwnQueuesIsAllowed(String destination) {
        assertThat(guard(false).check(subscribe(destination), guest)).isEqualTo(StompGuardResult.PASS);
        assertThat(guard(true).check(subscribe(destination), alice(Instant.now().plusSeconds(60))))
                .isEqualTo(StompGuardResult.PASS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/queue/event/g1/p2",          // another player (e.g. the proctor's private queue)
            "/queue/event/g1/proctor",
            "/queue/event/g2",             // another game
            "/queue/event/g2/p1",
            "/queue/event/g1/p1/extra",
            "/queue/event/g1/",
            "/queue/event",
            "/queue/event/*",
            "/queue/**",
            "/queue/errors",
            "/queue/errors-userws-2",      // another connection's resolved user queue
            "/user/p2/queue/errors",
            "/user/g1:p2/queue/errors",
            "/topic/anything",
            "/app/game/x",
            "/anything",
            "/queue/event//g1",
            "/queue/event/./g1"})
    void subscribeElsewhereIsForbidden(String destination) {
        assertRejected(() -> guard(false).check(subscribe(destination), guest), StompErrorCode.FORBIDDEN_DESTINATION);
    }

    @Test
    void subscribeWithoutPrincipalIsAuthRequired() {
        assertRejected(() -> guard(false).check(subscribe("/queue/event/g1"), null), StompErrorCode.AUTH_REQUIRED);
    }

    /* ------------------- SUBSCRIBE: expiry and bans (G-04) ------------------ */

    @Test
    void subscribeWithAnExpiredTokenIsTokenExpired() {
        Instant expiry = Instant.parse("2026-01-01T00:00:00Z");
        Clock after = Clock.fixed(expiry.plusSeconds(1), ZoneOffset.UTC);
        Clock before = Clock.fixed(expiry.minusSeconds(1), ZoneOffset.UTC);
        assertThat(guard(true, before).check(subscribe("/queue/event/g1"), alice(expiry)))
                .isEqualTo(StompGuardResult.PASS);
        assertRejected(() -> guard(true, after).check(subscribe("/queue/event/g1"), alice(expiry)),
                StompErrorCode.TOKEN_EXPIRED);
        assertRejected(() -> guard(true, after).check(subscribe("/queue/event/g1/p1"), alice(expiry)),
                StompErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void authorizationOnSubscribeRefreshesAnExpiredPrincipal() {
        StompPrincipal p = alice(Instant.now().minusSeconds(10));
        StompHeaderAccessor frame = frame(StompCommand.SUBSCRIBE, "/queue/event/g1",
                "Authorization", "Bearer " + issuer.user("kc-alice", "game:host"));
        assertThat(guard(true).check(frame, p)).isEqualTo(StompGuardResult.PASS);
        assertThat(p.getTokenExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void guestsAndAuthOffSkipTheExpiryCheckOnSubscribe() {
        StompPrincipal expired = alice(Instant.now().minusSeconds(10));
        assertThat(guard(false).check(subscribe("/queue/event/g1"), expired)).isEqualTo(StompGuardResult.PASS);
        assertThat(guard(true).check(subscribe("/queue/event/g1"), guest)).isEqualTo(StompGuardResult.PASS);
    }

    @Test
    void bannedUserCannotSubscribe() {
        BanService bans = mock(BanService.class);
        when(bans.isBanned("kc-alice")).thenReturn(true);
        StompDestinationGuard guard = new StompDestinationGuard(new GameAuthorizationPolicy(true, bans),
                provider(issuer.decoder()), Clock.systemUTC());

        assertRejected(() -> guard.check(subscribe("/queue/event/g1"), alice(Instant.now().plusSeconds(60))),
                StompErrorCode.BANNED);
        assertRejected(() -> guard.check(subscribe("/user/queue/errors"), alice(Instant.now().plusSeconds(60))),
                StompErrorCode.BANNED);
        // A guest carries no subject to ban.
        assertThat(guard.check(subscribe("/queue/event/g1"), guest)).isEqualTo(StompGuardResult.PASS);
        // Someone else is not affected.
        StompPrincipal bob = StompPrincipal.user(GAME, ME, "kc-bob", Set.of(), Instant.now().plusSeconds(60));
        assertThat(guard.check(subscribe("/queue/event/g1"), bob)).isEqualTo(StompGuardResult.PASS);
    }

    /* ---------------------------- other frames ---------------------------- */

    @Test
    void connectDisconnectUnsubscribeAndHeartbeatsPass() {
        assertThat(guard(false).check(frame(StompCommand.CONNECT, null), null)).isEqualTo(StompGuardResult.PASS);
        assertThat(guard(false).check(frame(StompCommand.DISCONNECT, null), null)).isEqualTo(StompGuardResult.PASS);
        assertThat(guard(false).check(frame(StompCommand.UNSUBSCRIBE, null), null)).isEqualTo(StompGuardResult.PASS);
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        assertThat(heartbeat.getMessageType()).isEqualTo(SimpMessageType.HEARTBEAT);
        assertThat(guard(false).check(heartbeat, null)).isEqualTo(StompGuardResult.PASS);
    }

    @Test
    void otherFramesWithoutPrincipalAreAuthRequired() {
        assertRejected(() -> guard(false).check(frame(StompCommand.ACK, null), null), StompErrorCode.AUTH_REQUIRED);
        assertRejected(() -> guard(false).check(frame(StompCommand.BEGIN, null), null), StompErrorCode.AUTH_REQUIRED);
    }
}
