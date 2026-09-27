package com.soulsoftworks.sockbowlgame.security.stomp;

import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlgame.controller.helper.StompTestClient;
import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.entity.UserGameHistory;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.repository.IpBanRepository;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.UserService;
import com.soulsoftworks.sockbowlgame.service.UserUsedQuestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * STOMP security end to end with {@code sockbowl.auth.enabled=true} (plan
 * m2-auth WP-G2; AUTH-02, AUTH-04, AUTH-16): signed-in players connect with
 * their own JWT, bans are enforced at CONNECT and on SEND, and handler-time
 * errors are non-fatal {@code StompError}s on {@code /user/queue/errors}. The
 * {@link JwtDecoder} is mocked (the real decoder is covered by
 * {@code StompConnectAuthenticatorTest}); JPA collaborators are mocked as in
 * {@code GameSessionControllerAuthIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/token",
        "spring.security.oauth2.client.registration.questions-svc.provider=keycloak",
        "spring.security.oauth2.client.registration.questions-svc.client-id=sockbowl-game-backend",
        "spring.security.oauth2.client.registration.questions-svc.client-secret=test-secret",
        "spring.security.oauth2.client.registration.questions-svc.authorization-grant-type=client_credentials",
        "sockbowl.questions.url=http://127.0.0.1:1/"
})
class StompSecurityAuthOnIT extends StompSecurityITSupport {

    @MockitoBean
    JwtDecoder jwtDecoder;
    @MockitoBean
    BanService banService;
    @MockitoBean
    UserService userService;
    @MockitoBean
    UserUsedQuestionService usedQuestionService;
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    UserGameHistoryRepository userGameHistoryRepository;
    // M4 IP bans (JPA is off in this context, so the repository is mocked too).
    @MockitoBean
    IpBanRepository ipBanRepository;

    private GameSession game;
    private JoinGameResponse alice;
    private JoinGameResponse mallory;
    private JoinGameResponse guest;

    private static Jwt jwt(String token, String sub, String azp, Instant expiresAt, String... roles) {
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(sub)
                .claim("azp", azp)
                .claim("preferred_username", sub)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .audience(List.of("sockbowl-api"))
                .issuedAt(expiresAt.minusSeconds(600))
                .expiresAt(expiresAt)
                .build();
    }

    private static Jwt user(String token, String sub) {
        return jwt(token, sub, "sockbowl-game", Instant.now().plusSeconds(300), "game:host", "packet:create");
    }

    @BeforeEach
    void seatPlayers() {
        when(banService.isBanned(anyString())).thenReturn(false);
        when(userRepository.findByKeycloakId(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
        when(userGameHistoryRepository.save(any(UserGameHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        when(jwtDecoder.decode("tok-alice")).thenReturn(user("tok-alice", "kc-alice"));
        when(jwtDecoder.decode("tok-bob")).thenReturn(user("tok-bob", "kc-bob"));
        when(jwtDecoder.decode("tok-mallory")).thenReturn(user("tok-mallory", "kc-mallory"));
        when(jwtDecoder.decode("tok-service")).thenReturn(jwt("tok-service", "kc-alice", "sockbowl-game-backend",
                Instant.now().plusSeconds(300), "packet:read-answers"));
        when(jwtDecoder.decode("tok-expired")).thenThrow(new JwtValidationException("An error occurred",
                List.of(new OAuth2Error("invalid_token", "Jwt expired at 2020-01-01T00:00:00Z", null))));
        when(jwtDecoder.decode("tok-garbage")).thenThrow(new BadJwtException("bad"));

        game = newGame();
        alice = sessionService.addAuthenticatedUserToGameSession(
                JoinGameRequest.builder().joinCode(game.getJoinCode()).name("Alice").build(),
                user("tok-alice", "kc-alice"));
        mallory = sessionService.addAuthenticatedUserToGameSession(
                JoinGameRequest.builder().joinCode(game.getJoinCode()).name("Mallory").build(),
                user("tok-mallory", "kc-mallory"));
        guest = joinAsGuest(game, "Guest");
    }

    private String playerQueue(JoinGameResponse player) {
        return "/queue/event/" + game.getId() + "/" + player.getPlayerSessionId();
    }

    @Test
    void signedInPlayerConnectsWithOwnJwtAndPlays() throws Exception {
        StompTestClient.Connection c = connect(game.getId(), alice.getPlayerSessionId(), null, "tok-alice");
        c.awaitConnected();
        BlockingQueue<String> mine = c.subscribe(playerQueue(alice));
        awaitSubscribed(playerQueue(alice), 1);

        JsonObject update = json(requestUntilReply(c, "/app/game/config/get-game", "{}", mine));
        assertThat(update.get("messageContentType").getAsString()).isEqualTo("GameSessionUpdate");
    }

    @Test
    void guestStillConnectsWithSecretWhenAuthIsOn() throws Exception {
        StompTestClient.Connection c = connect(game.getId(), guest.getPlayerSessionId(), guest.getPlayerSecret(), null);
        c.awaitConnected();
        BlockingQueue<String> mine = c.subscribe(playerQueue(guest));
        awaitSubscribed(playerQueue(guest), 1);
        assertThat(json(requestUntilReply(c, "/app/game/config/get-game", "{}", mine))
                .get("messageContentType").getAsString()).isEqualTo("GameSessionUpdate");
    }

    @Test
    void anotherUsersJwtIsIdentityMismatch() throws Exception {
        assertFatal(connect(game.getId(), alice.getPlayerSessionId(), null, "tok-bob"),
                StompErrorCode.IDENTITY_MISMATCH);
        assertFatal(connect(game.getId(), alice.getPlayerSessionId(), null, "tok-mallory"),
                StompErrorCode.IDENTITY_MISMATCH);
    }

    @Test
    void signedInSeatRequiresJwtEvenWithTheRightSecret() throws Exception {
        assertFatal(connect(game.getId(), alice.getPlayerSessionId(), alice.getPlayerSecret(), null),
                StompErrorCode.AUTH_REQUIRED);
    }

    @Test
    void bannedUserIsRejectedAtConnect() throws Exception {
        when(banService.isBanned("kc-mallory")).thenReturn(true);
        assertFatal(connect(game.getId(), mallory.getPlayerSessionId(), null, "tok-mallory"), StompErrorCode.BANNED);
    }

    @Test
    void serviceTokenExpiredAndInvalidTokensAreRejected() throws Exception {
        assertFatal(connect(game.getId(), alice.getPlayerSessionId(), null, "tok-service"),
                StompErrorCode.INVALID_CREDENTIALS);
        assertFatal(connect(game.getId(), alice.getPlayerSessionId(), null, "tok-expired"),
                StompErrorCode.TOKEN_EXPIRED);
        assertFatal(connect(game.getId(), alice.getPlayerSessionId(), null, "tok-garbage"),
                StompErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void handlerTimeErrorArrivesOnUserErrorQueueAndSocketStaysOpen() throws Exception {
        StompTestClient.Connection c = connect(game.getId(), alice.getPlayerSessionId(), null, "tok-alice");
        c.awaitConnected();
        BlockingQueue<String> errors = c.subscribe("/user/queue/errors");
        BlockingQueue<String> beats = c.subscribe("/queue/heartbeat");
        awaitSubscribed("/user/queue/errors", 1);
        awaitSubscribed("/queue/heartbeat", 1);

        // Alice is removed from the game after connecting: her next game message
        // fails verification in the argument resolver (PlayerVerificationException).
        GameSession live = sessionService.getGameSessionById(game.getId());
        live.getPlayerList().removeIf(p -> p.getPlayerId().equals(alice.getPlayerSessionId()));
        sessionService.saveGameSession(live);

        JsonObject error = json(requestUntilReply(c, "/app/game/config/get-game", "{}", errors));
        assertThat(error.get("messageType").getAsString()).isEqualTo("StompError");
        assertThat(error.get("code").getAsString()).isEqualTo("PLAYER_NOT_IN_SESSION");
        assertThat(error.has("retryAfterSeconds")).isTrue();

        // Non-fatal: the same socket keeps working.
        assertThat(c.isConnected()).isTrue();
        assertThat(requestUntilReply(c, "/app/heartbeat", "{}", beats)).contains("I am alive");
    }

    @Test
    void banIssuedMidGameIsAStompErrorOnSend() throws Exception {
        StompTestClient.Connection c = connect(game.getId(), mallory.getPlayerSessionId(), null, "tok-mallory");
        c.awaitConnected();
        BlockingQueue<String> errors = c.subscribe("/user/queue/errors");
        awaitSubscribed("/user/queue/errors", 1);

        when(banService.isBanned("kc-mallory")).thenReturn(true);

        JsonObject error = json(requestUntilReply(c, "/app/game/config/get-game", "{}", errors));
        assertThat(error.get("code").getAsString()).isEqualTo("BANNED");
    }
}
