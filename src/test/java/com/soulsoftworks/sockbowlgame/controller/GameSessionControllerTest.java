package com.soulsoftworks.sockbowlgame.controller;

import com.google.gson.Gson;
import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import com.soulsoftworks.sockbowlgame.controller.helper.GsonMessageConverterWithStringResponse;
import com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils;
import com.soulsoftworks.sockbowlgame.model.game.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.GameSettings;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.GameSessionIdentifiers;
import com.soulsoftworks.sockbowlgame.service.GameSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameSessionControllerTest {

    private final static Gson gson = new Gson();

    @MockBean
    GameSessionService gameSessionService;

    @Value("${local.server.port}")
    private int port;

    private static String GAME_SESSION_CREATED_TOPIC = "/user/topic/game-session-created";
    private static String CREATE_NEW_GAME_SESSION_APP = "/app/create-new-game-session";

    // Used for async
    private CompletableFuture<String> completableFuture;

    @BeforeEach
    void beforeEachSetup(){
        completableFuture = new CompletableFuture<>();
    }

    static {
        GenericContainer<?> redisContainer =
                new GenericContainer<>(DockerImageName.parse("redislabs/redisearch:latest"))
                        .withExposedPorts(6379);
        redisContainer.start();
        System.setProperty("redis-game-cache.host", redisContainer.getHost());
        System.setProperty("redis-game-cache.port", redisContainer.getMappedPort(6379).toString());
    }

    @Test
    void createNewGame_responseIsSentBackToSpecificSessions() throws ExecutionException, InterruptedException,
            TimeoutException {

        GameSession gameSession = GameSession.builder()
                .id("test")
                .joinCode("TEST")
                .gameSettings(new GameSettings())
                .build();

        when(gameSessionService.createNewGame(any(CreateGameRequest.class))).thenReturn(gameSession);

        // Setup web socket client
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(createTransportClient()));
        stompClient.setMessageConverter(new GsonMessageConverterWithStringResponse());
        String url =  "ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT;

        StompSession stompSession = stompClient.connect(url, new StompSessionHandlerAdapter(){})
                .get(1, SECONDS);

        // Subscribe to response endpoint
        stompSession.subscribe(GAME_SESSION_CREATED_TOPIC, new WebSocketUtils.SimpleStompFrameHandler(completableFuture));

        // Send to app endpoint
        stompSession.send(CREATE_NEW_GAME_SESSION_APP, new CreateGameRequest());

        // Wait for value
        String response = completableFuture.get(10, SECONDS);

        // Assert that response is as we expected
        GameSessionIdentifiers expectedGameSessionIdentifiers = GameSessionIdentifiers.builder()
                .fromGameSession(gameSession)
                .build();

        assertEquals(expectedGameSessionIdentifiers, gson.fromJson(response, GameSessionIdentifiers.class));
    }


    /*@Test
    void joinGame() throws ExecutionException, InterruptedException,
            TimeoutException {

        CreateGameRequest createGameRequest = new CreateGameRequest();

        // Create new session in Redis and get it back from the method call
        GameSession gameSession = gameSessionService.createNewGame(createGameRequest);

        // Get game session out of redis using the ID from createNewGame()
        GameSession gameSessionFromRedis = gameSessionService.getGameSessionById(gameSession.getId());


        // Setup web socket client
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(createTransportClient()));
        stompClient.setMessageConverter(new GsonMessageConverterWithStringResponse());
        String url =  "ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT;

        StompSession stompSession = stompClient.connect(url, new StompSessionHandlerAdapter(){})
                .get(1, SECONDS);

        // Subscribe to response endpoint
        stompSession.subscribe(GAME_SESSION_CREATED_TOPIC, new WebSocketUtils.SimpleStompFrameHandler(completableFuture));

        // Send to app endpoint
        stompSession.send(CREATE_NEW_GAME_SESSION_APP, new CreateGameRequest());

        // Wait for value
        String response = completableFuture.get(10, SECONDS);

        // Assert that response is as we expected
        GameSessionIdentifiers expectedGameSessionIdentifiers = GameSessionIdentifiers.builder()
                .fromGameSession(gameSession)
                .build();

        assertEquals(expectedGameSessionIdentifiers, gson.fromJson(response, GameSessionIdentifiers.class));
    }*/
}
