package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;

import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.frame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CONNECT authentication matrix (plan m2-auth WP-G2, section 2.5): the guest
 * secret path, the JWT path with the real audience-bound decoder, and auth-off.
 */
class StompConnectAuthenticatorTest {

    private static final String GAME = "game-1";
    private static final String GUEST = "p-guest";
    private static final String ALICE = "p-alice";
    private static final String SECRET = "s3cret-guest";

    private TestJwtIssuer issuer;
    private SessionService sessionService;
    private BanService banService;

    @BeforeEach
    void setUp() throws Exception {
        issuer = new TestJwtIssuer();
        sessionService = mock(SessionService.class);
        banService = mock(BanService.class);
        when(banService.isBanned(anyString())).thenReturn(false);

        GameSession session = GameSession.builder().id(GAME).joinCode("ABCDEF").gameSettings(new GameSettings()).build();
        session.getPlayerList().add(Player.builder().playerId(GUEST).playerSecret(SECRET).isGuest(true).build());
        session.getPlayerList().add(Player.builder().playerId(ALICE).playerSecret("alice-secret")
                .keycloakId("kc-alice").isGuest(false).build());
        when(sessionService.getGameSessionById(GAME)).thenReturn(session);
    }

    @AfterEach
    void tearDown() throws Exception {
        issuer.close();
    }

    private StompConnectAuthenticator authenticator(boolean authEnabled) {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(authEnabled, authEnabled ? banService : null);
        return new StompConnectAuthenticator(sessionService, policy, provider(authEnabled ? issuer.decoder() : null));
    }

    static ObjectProvider<JwtDecoder> provider(JwtDecoder decoder) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (decoder != null) {
            factory.addBean("jwtDecoder", decoder);
        }
        return factory.getBeanProvider(JwtDecoder.class);
    }

    private static StompHeaderAccessor connect(String... headers) {
        return frame(StompCommand.CONNECT, null, headers);
    }

    private static void assertRejected(Runnable call, StompErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(StompRejectedException.class)
                .extracting(e -> ((StompRejectedException) e).getCode())
                .isEqualTo(code);
    }

    /* ----------------------------- guest path ----------------------------- */

    @Test
    void guestWithGoodSecretGetsGuestPrincipal() {
        for (boolean authEnabled : new boolean[]{false, true}) {
            StompPrincipal p = authenticator(authEnabled).authenticate(
                    connect("gameSessionId", GAME, "playerSessionId", GUEST, "playerSecret", SECRET));
            assertThat(p.isGuest()).isTrue();
            assertThat(p.getName()).isEqualTo(GAME + ":" + GUEST);
            assertThat(p.getKeycloakId()).isNull();
            assertThat(p.getAuthorities()).isEmpty();
            assertThat(p.getTokenExpiresAt()).isNull();
        }
    }

    @Test
    void guestWithBadSecretIsInvalidCredentials() {
        assertRejected(() -> authenticator(false).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", GUEST, "playerSecret", "wrong")),
                StompErrorCode.INVALID_CREDENTIALS);
        assertRejected(() -> authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", GUEST, "playerSecret", SECRET + "x")),
                StompErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void guestWithMissingSecretIsAuthRequired() {
        assertRejected(() -> authenticator(false).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", GUEST)), StompErrorCode.AUTH_REQUIRED);
    }

    @Test
    void noHeadersAtAllIsAuthRequired() {
        assertRejected(() -> authenticator(false).authenticate(connect()), StompErrorCode.AUTH_REQUIRED);
        assertRejected(() -> authenticator(true).authenticate(connect("gameSessionId", GAME)),
                StompErrorCode.AUTH_REQUIRED);
    }

    @Test
    void unknownGameIsSessionNotFound() {
        assertRejected(() -> authenticator(false).authenticate(
                connect("gameSessionId", "nope", "playerSessionId", GUEST, "playerSecret", SECRET)),
                StompErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void unknownPlayerIsPlayerNotInSession() {
        assertRejected(() -> authenticator(false).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", "p-ghost", "playerSecret", SECRET)),
                StompErrorCode.PLAYER_NOT_IN_SESSION);
    }

    @Test
    void guestSecretIsNotAcceptedForAnotherPlayer() {
        // Guest's own secret against the signed-in player's seat (auth off: secret path).
        assertRejected(() -> authenticator(false).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "playerSecret", SECRET)),
                StompErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void jwtOnGuestSeatIsIgnoredAndSecretStillRequired() {
        String aliceToken = issuer.user("kc-alice", "game:host");
        StompPrincipal p = authenticator(true).authenticate(connect("gameSessionId", GAME, "playerSessionId", GUEST,
                "Authorization", "Bearer " + aliceToken, "playerSecret", SECRET));
        assertThat(p.isGuest()).isTrue();
        assertThat(p.getKeycloakId()).isNull();

        assertRejected(() -> authenticator(true).authenticate(connect("gameSessionId", GAME,
                "playerSessionId", GUEST, "Authorization", "Bearer " + aliceToken)), StompErrorCode.AUTH_REQUIRED);
    }

    /* ------------------------------ JWT path ------------------------------ */

    @Test
    void signedInPlayerWithOwnTokenGetsUserPrincipal() {
        String token = issuer.user("kc-alice", "game:host", "packet:create");
        StompPrincipal p = authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "Authorization", "Bearer " + token));
        assertThat(p.isGuest()).isFalse();
        assertThat(p.getKeycloakId()).isEqualTo("kc-alice");
        assertThat(p.getAuthorities()).containsExactlyInAnyOrder("game:host", "packet:create");
        assertThat(p.getTokenExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void signedInPlayerWithoutTokenIsAuthRequired() {
        // Even with the right player secret: a JWT seat needs the JWT.
        assertRejected(() -> authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "playerSecret", "alice-secret")),
                StompErrorCode.AUTH_REQUIRED);
    }

    @Test
    void anotherUsersTokenIsIdentityMismatch() {
        String bob = issuer.user("kc-bob", "game:host");
        assertRejected(() -> authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "Authorization", "Bearer " + bob)),
                StompErrorCode.IDENTITY_MISMATCH);
    }

    @Test
    void expiredTokenIsTokenExpired() {
        String expired = issuer.token("kc-alice", "sockbowl-game", List.of(TestJwtIssuer.AUDIENCE),
                Instant.now().minusSeconds(3600), "game:host");
        assertRejected(() -> authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "Authorization", "Bearer " + expired)),
                StompErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void wrongAudienceTokenIsInvalidCredentials() {
        String wrongAud = issuer.token("kc-alice", "sockbowl-game", List.of("account"),
                Instant.now().plusSeconds(300), "game:host");
        assertRejected(() -> authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "Authorization", "Bearer " + wrongAud)),
                StompErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void garbageAndMalformedHeadersAreInvalidCredentials() {
        assertRejected(() -> authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "Authorization", "Bearer not.a.jwt")),
                StompErrorCode.INVALID_CREDENTIALS);
        assertRejected(() -> authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "Authorization", "Basic abc")),
                StompErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void serviceTokenIsInvalidCredentials() {
        // Even a service token whose sub happened to equal the player's keycloakId.
        String service = issuer.token("kc-alice", "sockbowl-game-backend", List.of(TestJwtIssuer.AUDIENCE),
                Instant.now().plusSeconds(300), "packet:read-answers");
        assertRejected(() -> authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "Authorization", "Bearer " + service)),
                StompErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void bannedUserIsBanned() {
        when(banService.isBanned("kc-alice")).thenReturn(true);
        String token = issuer.user("kc-alice", "game:host");
        assertRejected(() -> authenticator(true).authenticate(
                connect("gameSessionId", GAME, "playerSessionId", ALICE, "Authorization", "Bearer " + token)),
                StompErrorCode.BANNED);
    }

    @Test
    void authOffIgnoresJwtAndUsesSecret() {
        // With auth off there is no decoder; a JWT header must be ignored, not decoded.
        String token = issuer.user("kc-alice", "game:host");
        StompPrincipal p = authenticator(false).authenticate(connect("gameSessionId", GAME, "playerSessionId", ALICE,
                "Authorization", "Bearer " + token, "playerSecret", "alice-secret"));
        assertThat(p.isGuest()).isTrue();
        assertThat(p.getAuthorities()).isEmpty();
        verify(banService, never()).isBanned(anyString());

        assertRejected(() -> authenticator(false).authenticate(connect("gameSessionId", GAME,
                "playerSessionId", ALICE, "Authorization", "Bearer " + token)), StompErrorCode.AUTH_REQUIRED);
    }

    @Test
    void constantTimeCompareHandlesNulls() {
        assertThat(StompConnectAuthenticator.constantTimeEquals(null, "x")).isFalse();
        assertThat(StompConnectAuthenticator.constantTimeEquals("x", null)).isFalse();
        assertThat(StompConnectAuthenticator.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(StompConnectAuthenticator.constantTimeEquals("abc", "abd")).isFalse();
    }
}
