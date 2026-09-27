package com.soulsoftworks.sockbowlgame.controller.api;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.controller.helper.StompTestClient;
import com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.ProctorType;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The heartbeat round trip over a real STOMP connection. Since M2 WP-G2 every
 * connection must authenticate at CONNECT (here as a guest, with the player
 * secret), so the test seats a player first.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeartbeatControllerTest {

    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private SessionService sessionService;

    private static final String HEARTBEAT_TOPIC = "/queue/heartbeat";
    private static final String HEARTBEAT_APP = "/app/heartbeat";

    private StompTestClient client;
    private StompTestClient.Connection connection;

    @BeforeEach
    void setup() throws Exception {
        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.QUIZ_BOWL_CLASSIC);
        settings.setProctorType(ProctorType.IN_PERSON_PROCTOR);
        CreateGameRequest create = new CreateGameRequest();
        create.setGameSettings(settings);
        GameSession game = sessionService.createNewGame(create);
        JoinGameResponse player = sessionService.addPlayerToGameSessionWithJoinCode(
                JoinGameRequest.builder().joinCode(game.getJoinCode()).name("Pinger").build());

        client = new StompTestClient(port);
        connection = client.connect(WebSocketUtils.connectHeaders(
                game.getId(), player.getPlayerSessionId(), player.getPlayerSecret()));
        connection.awaitConnected();
    }

    @AfterEach
    void tearDown() {
        connection.disconnect();
        client.stop();
    }

    @Test
    void heartbeat_returnsExpectedValue() throws Exception {
        BlockingQueue<String> beats = connection.subscribe(HEARTBEAT_TOPIC);

        // The SUBSCRIBE and SEND are dispatched on different threads; resend
        // until the subscription is live.
        String response = null;
        for (int attempt = 0; attempt < 10 && response == null; attempt++) {
            connection.send(HEARTBEAT_APP, "{}");
            response = beats.poll(1, TimeUnit.SECONDS);
        }

        assertEquals("Hello! I am alive!", response);
    }
}
