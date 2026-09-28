package com.soulsoftworks.sockbowlgame.security.stomp;

import com.google.gson.JsonObject;
import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlgame.controller.helper.StompTestClient;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.MatchState;
import com.soulsoftworks.sockbowlgame.model.state.PlayerMode;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R3-G-01 over real STOMP: from packet load through a finished match and a
 * get-game afterwards, no frame a non-proctor receives (on the game topic or
 * their own queue) carries the packet id; the proctor's own queue does, with
 * the counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sockbowl.auth.enabled=false")
class PacketIdOverStompIT extends StompSecurityITSupport {

    @MockitoBean
    PacketClient packetClient;

    GameSession game;
    JoinGameResponse proctor;
    JoinGameResponse alice;
    JoinGameResponse bob;

    @BeforeEach
    void seat() {
        when(packetClient.getPacketById(any())).thenAnswer(inv -> Mono.just(InSessionFixture.packet()));
        game = newGame();
        proctor = joinAsGuest(game, "Proctor");
        alice = joinAsGuest(game, "Alice");
        bob = joinAsGuest(game, "Bob");
    }

    private StompTestClient.Connection as(JoinGameResponse p) throws Exception {
        StompTestClient.Connection c = connect(game.getId(), p.getPlayerSessionId(), p.getPlayerSecret(), null);
        c.awaitConnected();
        return c;
    }

    private String queue(JoinGameResponse p) {
        return "/queue/event/" + game.getId() + "/" + p.getPlayerSessionId();
    }

    private String topic() {
        return "/queue/event/" + game.getId();
    }

    private void awaitSession(Predicate<GameSession> condition) {
        await().atMost(10, TimeUnit.SECONDS).pollInterval(20, TimeUnit.MILLISECONDS)
                .until(() -> condition.test(sessionService.getGameSessionById(game.getId())));
    }

    private static List<String> drain(BlockingQueue<String> queue) throws InterruptedException {
        List<String> out = new ArrayList<>();
        String s;
        while ((s = queue.poll(300, TimeUnit.MILLISECONDS)) != null) {
            out.add(s);
        }
        return out;
    }

    @Test
    @DisplayName("No non-proctor STOMP frame carries the packet id; the proctor's MatchPacketUpdate does")
    void packetIdReachesOnlyTheProctor() throws Exception {
        StompTestClient.Connection pc = as(proctor);
        StompTestClient.Connection ac = as(alice);
        StompTestClient.Connection bc = as(bob);
        BlockingQueue<String> proctorQueue = pc.subscribe(queue(proctor));
        BlockingQueue<String> aliceQueue = ac.subscribe(queue(alice));
        BlockingQueue<String> aliceTopic = ac.subscribe(topic());
        BlockingQueue<String> bobQueue = bc.subscribe(queue(bob));
        BlockingQueue<String> bobTopic = bc.subscribe(topic());
        awaitSubscribed(queue(proctor), 1);
        awaitSubscribed(queue(alice), 1);
        awaitSubscribed(queue(bob), 1);
        awaitSubscribed(topic(), 2);

        GameSession s = sessionService.getGameSessionById(game.getId());
        String team1 = s.getTeamList().get(0).getTeamId();
        String team2 = s.getTeamList().get(1).getTeamId();
        pc.send("/app/game/config/set-proctor", "{\"targetPlayer\":\"" + proctor.getPlayerSessionId() + "\"}");
        awaitSession(g -> g.getPlayerModeById(proctor.getPlayerSessionId()) == PlayerMode.PROCTOR);
        ac.send("/app/game/config/update-player-team",
                "{\"targetPlayer\":\"" + alice.getPlayerSessionId() + "\",\"targetTeam\":\"" + team1 + "\"}");
        bc.send("/app/game/config/update-player-team",
                "{\"targetPlayer\":\"" + bob.getPlayerSessionId() + "\",\"targetTeam\":\"" + team2 + "\"}");
        awaitSession(g -> g.getPlayerModeById(alice.getPlayerSessionId()) == PlayerMode.BUZZER
                && g.getPlayerModeById(bob.getPlayerSessionId()) == PlayerMode.BUZZER);
        pc.send("/app/game/config/set-match-packet", "{\"packetId\":\"" + InSessionFixture.PACKET_ID + "\"}");
        awaitSession(g -> InSessionFixture.PACKET_ID.equals(g.loadedPacketId()));
        pc.send("/app/game/progression/start-match", "{}");
        awaitSession(g -> g.getCurrentMatch().getMatchState() == MatchState.IN_GAME);
        pc.send("/app/game/progression/end-match", "{}");
        awaitSession(g -> !g.getPreviousMatches().isEmpty());
        ac.send("/app/game/config/get-game", "{}");
        bc.send("/app/game/config/get-game", "{}");

        List<String> nonProctor = new ArrayList<>();
        List<String> aliceFrames = drain(aliceQueue);
        nonProctor.addAll(aliceFrames);
        nonProctor.addAll(drain(aliceTopic));
        nonProctor.addAll(drain(bobQueue));
        nonProctor.addAll(drain(bobTopic));
        List<String> proctorFrames = drain(proctorQueue);

        assertThat(nonProctor).as("non-proctor frames").isNotEmpty();
        for (String frame : nonProctor) {
            assertThat(frame).as("a non-proctor frame carries the packet id").doesNotContain(InSessionFixture.PACKET_ID);
        }
        JsonObject aliceUpdate = aliceFrames.stream().map(StompSecurityITSupport::json)
                .filter(j -> "MatchPacketUpdate".equals(j.get("messageContentType").getAsString()))
                .findFirst().orElseThrow(() -> new AssertionError("alice got no MatchPacketUpdate: " + aliceFrames));
        assertThat(aliceUpdate.get("packetName").getAsString()).isEqualTo("Fixture Packet");
        assertThat(aliceUpdate.get("tossupCount").getAsInt()).isEqualTo(2);
        assertThat(aliceUpdate.get("bonusCount").getAsInt()).isEqualTo(2);
        assertThat(aliceUpdate.has("packetId") && !aliceUpdate.get("packetId").isJsonNull()).isFalse();
        assertThat(aliceFrames.stream().filter(f -> f.contains("\"GameSessionUpdate\"")).count())
                .as("alice got end-match and get-game session updates").isGreaterThanOrEqualTo(2);

        JsonObject proctorUpdate = proctorFrames.stream().map(StompSecurityITSupport::json)
                .filter(j -> "MatchPacketUpdate".equals(j.get("messageContentType").getAsString()))
                .findFirst().orElseThrow(() -> new AssertionError("the proctor got no MatchPacketUpdate"));
        assertThat(proctorUpdate.get("packetId").getAsString()).isEqualTo(InSessionFixture.PACKET_ID);
        assertThat(proctorUpdate.get("bonusCount").getAsInt()).isEqualTo(2);
    }
}
