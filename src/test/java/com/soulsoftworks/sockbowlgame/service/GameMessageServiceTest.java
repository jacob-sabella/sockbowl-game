package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import com.soulsoftworks.sockbowlgame.controller.helper.GsonMessageConverterWithStringResponse;
import com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils;
import com.soulsoftworks.sockbowlgame.model.game.config.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.config.PlayerMode;
import com.soulsoftworks.sockbowlgame.model.game.message.MessageQueues;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils.createTransportClient;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameMessageServiceTest {

    @Autowired
    private GameSessionService gameSessionService;

    @Autowired
    private GameMessageService gameMessageService;

    @Value("${local.server.port}")
    private int port;

    private GameSession gameSession;

    private StompSession stompSession;
    private CompletableFuture<String> completableFuture;

    static {
        GenericContainer<?> redisContainer =
                new GenericContainer<>(DockerImageName.parse("redislabs/redisearch:latest"))
                        .withExposedPorts(6379);
        redisContainer.start();
        System.setProperty("redis-game-cache.host", redisContainer.getHost());
        System.setProperty("redis-game-cache.port", redisContainer.getMappedPort(6379).toString());
    }


    @BeforeEach
    private void beforeEach() throws ExecutionException, InterruptedException, TimeoutException {
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

    @Test
    public void sendGameStateToPlayer_ItWorks() throws ExecutionException, InterruptedException, TimeoutException {

        StompHeaders stompHeaders = new StompHeaders();
        stompHeaders.setId(gameSession.getPlayerList().get(0).getPlayerId());
        stompHeaders.setDestination("/user/" + gameSession.getPlayerList().get(0).getPlayerId() + "/" + MessageQueues.GAME_STATE_QUEUE);

        // Subscribe to response endpoint
        stompSession.subscribe(stompHeaders,
                new WebSocketUtils.SimpleStompFrameHandler(completableFuture));

        gameMessageService.sendGameStateToPlayer(stompSession.getSessionId());

        // Wait for value
        String response = completableFuture.get(10, SECONDS);

        System.out.println("test");
    }
}