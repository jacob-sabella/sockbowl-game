package com.soulsoftworks.sockbowlgame.controller;

import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import com.soulsoftworks.sockbowlgame.model.game.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.GameSettings;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.service.GameSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.messaging.converter.GsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static com.soulsoftworks.sockbowlgame.controller.WebSocketUtils.createTransportClient;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameSessionControllerTest {

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

    @Test
    void createNewGame() throws ExecutionException, InterruptedException, TimeoutException {
        GameSession gameSession = GameSession.builder()
                .id("test")
                .joinCode("TEST")
                .gameSettings(new GameSettings())
                .build();

        when(gameSessionService.createNewGame(any(CreateGameRequest.class))).thenReturn(gameSession);

        // Setup web socket client
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(createTransportClient()));
        stompClient.setMessageConverter(new GsonMessageConverter());
        String url =  "ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT;
        StompSession stompSession = stompClient.connect(url, new StompSessionHandlerAdapter(){})
                .get(1, SECONDS);

        // Send to app endpoint
        stompSession.send(CREATE_NEW_GAME_SESSION_APP, new CreateGameRequest());

        // Subscribe to response endpoint
        stompSession.subscribe(GAME_SESSION_CREATED_TOPIC, new WebSocketUtils.SimpleStompFrameHandler(completableFuture));

        // Wait for value
        String response = completableFuture.get(10, SECONDS);

        // Assert that response is as we expected
        assertEquals("Hello! I am alive!", response);
    }
}
