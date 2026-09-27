package com.soulsoftworks.sockbowlgame.security.stomp;

import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlgame.controller.helper.StompTestClient;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompHeaders;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STOMP security end to end with {@code sockbowl.auth.enabled=false} (plan
 * m2-auth WP-G2; AUTH-01, AUTH-02, AUTH-16). Guest secrets are validated at
 * CONNECT and destinations are locked down even with auth off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sockbowl.auth.enabled=false")
class StompSecurityIT extends StompSecurityITSupport {

    private GameSession game;
    private JoinGameResponse alice;
    private JoinGameResponse bob;

    @BeforeEach
    void seatPlayers() {
        game = newGame();
        alice = joinAsGuest(game, "Alice");
        bob = joinAsGuest(game, "Bob");
    }

    private StompTestClient.Connection connectAs(JoinGameResponse player) throws Exception {
        StompTestClient.Connection c = connect(game.getId(), player.getPlayerSessionId(), player.getPlayerSecret(), null);
        c.awaitConnected();
        return c;
    }

    private String playerQueue(JoinGameResponse player) {
        return "/queue/event/" + game.getId() + "/" + player.getPlayerSessionId();
    }

    private String gameQueue() {
        return "/queue/event/" + game.getId();
    }

    @Test
    void happyPathGuestReceivesGameSessionUpdate() throws Exception {
        StompTestClient.Connection c = connectAs(alice);
        BlockingQueue<String> mine = c.subscribe(playerQueue(alice));
        c.subscribe(gameQueue());
        awaitSubscribed(playerQueue(alice), 1);

        JsonObject update = json(requestUntilReply(c, "/app/game/config/get-game", "{}", mine));
        assertThat(update.get("messageContentType").getAsString()).isEqualTo("GameSessionUpdate");
        assertThat(update.getAsJsonObject("gameSession").get("id").getAsString()).isEqualTo(game.getId());
        assertThat(c.isConnected()).isTrue();
    }

    @Test
    void forgedSendToBrokerQueueIsForbiddenAndNeverDelivered() throws Exception {
        StompTestClient.Connection victim = connectAs(bob);
        BlockingQueue<String> broadcast = victim.subscribe(gameQueue());
        BlockingQueue<String> bobsQueue = victim.subscribe(playerQueue(bob));
        awaitSubscribed(gameQueue(), 1);
        awaitSubscribed(playerQueue(bob), 1);

        StompTestClient.Connection attacker = connectAs(alice);
        String forged = "{\"messageContentType\":\"GameSessionUpdate\",\"forged\":true}";
        attacker.send(gameQueue(), forged);
        assertFatal(attacker, StompErrorCode.FORBIDDEN_DESTINATION);

        StompTestClient.Connection attacker2 = connectAs(alice);
        attacker2.send(playerQueue(bob), forged);
        assertFatal(attacker2, StompErrorCode.FORBIDDEN_DESTINATION);

        StompTestClient.Connection attacker3 = connectAs(alice);
        attacker3.send("/user/" + game.getId() + ":" + bob.getPlayerSessionId() + "/queue/errors", forged);
        assertFatal(attacker3, StompErrorCode.FORBIDDEN_DESTINATION);

        assertThat(broadcast.poll(1500, TimeUnit.MILLISECONDS)).as("forged broadcast delivered").isNull();
        assertThat(bobsQueue.poll(200, TimeUnit.MILLISECONDS)).as("forged private message delivered").isNull();
        assertThat(victim.isConnected()).isTrue();
    }

    @Test
    void connectWithoutHeadersIsAuthRequired() throws Exception {
        StompTestClient.Connection c = client.connect(new StompHeaders());
        assertFatal(c, StompErrorCode.AUTH_REQUIRED);
    }

    @Test
    void connectWithoutSecretIsAuthRequired() throws Exception {
        assertFatal(connect(game.getId(), alice.getPlayerSessionId(), null, null), StompErrorCode.AUTH_REQUIRED);
    }

    @Test
    void connectWithBadSecretIsInvalidCredentials() throws Exception {
        assertFatal(connect(game.getId(), alice.getPlayerSessionId(), bob.getPlayerSecret(), null),
                StompErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void connectToUnknownGameOrPlayerIsRejected() throws Exception {
        assertFatal(connect("no-such-game", alice.getPlayerSessionId(), alice.getPlayerSecret(), null),
                StompErrorCode.SESSION_NOT_FOUND);
        assertFatal(connect(game.getId(), "no-such-player", alice.getPlayerSecret(), null),
                StompErrorCode.PLAYER_NOT_IN_SESSION);
    }

    @Test
    void subscribeToAnotherPlayersQueueIsForbidden() throws Exception {
        // Bob is the proctor: his private queue carries the answers.
        StompTestClient.Connection c = connectAs(alice);
        c.subscribe(playerQueue(bob));
        assertFatal(c, StompErrorCode.FORBIDDEN_DESTINATION);
    }

    @Test
    void subscribeToAnotherGameIsForbidden() throws Exception {
        GameSession other = newGame();
        StompTestClient.Connection c = connectAs(alice);
        c.subscribe("/queue/event/" + other.getId());
        assertFatal(c, StompErrorCode.FORBIDDEN_DESTINATION);
    }

    @Test
    void spoofedIdentityHeaderOnSendIsIdentityMismatch() throws Exception {
        StompTestClient.Connection c = connectAs(alice);
        c.send("/app/game/config/get-game", "{}", "playerSessionId", bob.getPlayerSessionId());
        assertFatal(c, StompErrorCode.IDENTITY_MISMATCH);
    }

    @Test
    void forgedIdentityInBodyIsOverwrittenByPrincipal() throws Exception {
        // Alice asks for the game state while claiming, in the body, to be Bob.
        StompTestClient.Connection c = connectAs(alice);
        BlockingQueue<String> mine = c.subscribe(playerQueue(alice));
        StompTestClient.Connection b = connectAs(bob);
        BlockingQueue<String> bobs = b.subscribe(playerQueue(bob));
        awaitSubscribed(playerQueue(alice), 1);
        awaitSubscribed(playerQueue(bob), 1);

        String forgedBody = "{\"originatingPlayerId\":\"" + bob.getPlayerSessionId()
                + "\",\"gameSessionId\":\"other-game\""
                + ",\"originatingAuthorities\":[\"packet:manage-any\"],\"originatingKeycloakId\":\"kc-admin\"}";
        JsonObject reply = json(requestUntilReply(c, "/app/game/config/get-game", forgedBody, mine));

        assertThat(reply.get("messageContentType").getAsString()).isEqualTo("GameSessionUpdate");
        assertThat(reply.getAsJsonObject("gameSession").get("id").getAsString()).isEqualTo(game.getId());
        assertThat(bobs.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void heartbeatStillWorksForConnectedGuests() throws Exception {
        StompTestClient.Connection c = connectAs(alice);
        BlockingQueue<String> beats = c.subscribe("/queue/heartbeat");
        awaitSubscribed("/queue/heartbeat", 1);
        String beat = requestUntilReply(c, "/app/heartbeat", "{}", beats);
        assertThat(beat).contains("I am alive");
    }
}
