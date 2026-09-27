package com.soulsoftworks.sockbowlgame.security.stomp;

import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlgame.controller.helper.StompTestClient;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Team;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Frames one client sends are handled in the order it sent them. Spring
 * dispatches inbound STOMP frames on a thread pool, so without
 * {@code preserveReceiveOrder} two back-to-back SENDs can be handled (and
 * produced to Kafka) in either order. full-match hit this: the proctor's
 * set-proctor and set-match-packet were swapped, the packet was refused
 * because the sender was not proctor yet, and staging timed out.
 *
 * <p>The test sends a run of team switches that alternate between the two
 * teams. In order, every switch is valid and the player ends on the team of
 * the last one; any reordering produces "Player already on team" errors and a
 * random final team.
 */
// The extra property gives this class its own Spring context. The Redis
// container is a static field of the shared support class, restarted (on a new
// port) for every test class, so sharing StompSecurityIT's cached context would
// leave one of the two classes pointing at a stopped container.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sockbowl.auth.enabled=false", "sockbowl.test.context=stomp-receive-order"})
@DirtiesContext
class StompReceiveOrderIT extends StompSecurityITSupport {

    private static final int SWITCHES = 40;

    @Test
    void framesFromOneClientAreHandledInSendOrder() throws Exception {
        GameSession game = newGame();
        JoinGameResponse alice = joinAsGuest(game, "Alice");
        List<Team> teams = sessionService.getGameSessionById(game.getId()).getTeamList();
        String teamA = teams.get(0).getTeamId();
        String teamB = teams.get(1).getTeamId();

        StompTestClient.Connection c = connect(game.getId(), alice.getPlayerSessionId(), alice.getPlayerSecret(), null);
        c.awaitConnected();
        String gameQueue = "/queue/event/" + game.getId();
        String playerQueue = gameQueue + "/" + alice.getPlayerSessionId();
        BlockingQueue<String> broadcasts = c.subscribe(gameQueue);
        BlockingQueue<String> direct = c.subscribe(playerQueue);
        awaitSubscribed(gameQueue, 1);
        awaitSubscribed(playerQueue, 1);

        String lastTeam = null;
        for (int i = 0; i < SWITCHES; i++) {
            lastTeam = i % 2 == 0 ? teamA : teamB;
            c.send("/app/game/config/update-player-team",
                    "{\"targetPlayer\":\"" + alice.getPlayerSessionId() + "\",\"targetTeam\":\"" + lastTeam + "\"}");
        }

        // Every switch answers with a roster broadcast, or an error to the sender.
        List<String> errors = new ArrayList<>();
        int rosterUpdates = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (rosterUpdates + errors.size() < SWITCHES && System.nanoTime() < deadline) {
            String roster = broadcasts.poll(10, TimeUnit.MILLISECONDS);
            if (roster != null && "PlayerRosterUpdate".equals(json(roster).get("messageContentType").getAsString())) {
                rosterUpdates++;
            }
            String error = direct.poll(10, TimeUnit.MILLISECONDS);
            if (error != null) {
                JsonObject e = json(error);
                errors.add(e.has("error") ? e.get("error").getAsString() : error);
            }
        }

        assertThat(errors).as("errors from out-of-order handling").isEmpty();
        assertThat(rosterUpdates).isEqualTo(SWITCHES);
        GameSession stored = sessionService.getGameSessionById(game.getId());
        assertThat(stored.getTeamByPlayerId(alice.getPlayerSessionId()).getTeamId()).isEqualTo(lastTeam);
    }
}
