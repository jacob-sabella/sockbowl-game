package com.soulsoftworks.sockbowlgame.service;

import com.google.gson.Gson;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.TestcontainersUtil;
import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import com.soulsoftworks.sockbowlgame.controller.helper.GsonMessageConverterWithStringResponse;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.PlayerMode;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils.createTransportClient;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ARCHIVE_GameMessageServiceTest {

    @Autowired
    private GameSessionService gameSessionService;

    @Value("${local.server.port}")
    private int port;

    private GameSession gameSession;

    private StompSession stompSession;
    private CompletableFuture<String> completableFuture;
    private static final Gson gson = new Gson();

    @Container
    private static final RedisContainer REDIS_CONTAINER = TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    private static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.host", REDIS_CONTAINER::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS_CONTAINER.getMappedPort(6379).toString());
    }

    @BeforeEach
    public void beforeEach() throws ExecutionException, InterruptedException, TimeoutException {
        CreateGameRequest createGameRequest = new CreateGameRequest();
        createGameRequest.getGameSettings().setNumPlayers(8);

        gameSession = gameSessionService.createNewGame(createGameRequest);

        // Setup web socket client
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(createTransportClient()));
        stompClient.setMessageConverter(new GsonMessageConverterWithStringResponse());
        String stompUrl = "ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT;

        stompSession = stompClient.connect(stompUrl, new StompSessionHandlerAdapter(){}).get(10, SECONDS);

        // Create join game request
        JoinGameRequest joinGameRequest = new JoinGameRequest();
        joinGameRequest.setJoinCode(gameSession.getJoinCode());
        joinGameRequest.setPlayerSessionId(stompSession.getSessionId());
        joinGameRequest.setPlayerMode(PlayerMode.BUZZER_ONLY);
        joinGameRequest.setName("Jimmy");

        // Add player to game
        gameSessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);

        // Get updated game session
        gameSession = gameSessionService.getGameSessionById(gameSession.getId());

        // Create new completable future
        completableFuture = new CompletableFuture<>();
    }

}