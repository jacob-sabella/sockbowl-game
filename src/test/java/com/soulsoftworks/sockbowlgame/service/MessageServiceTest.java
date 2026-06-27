package com.soulsoftworks.sockbowlgame.service;

import com.google.gson.Gson;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import com.soulsoftworks.sockbowlgame.controller.helper.GsonMessageConverterWithStringResponse;
import com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageQueues;
import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils.createTransportClient;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
@Disabled
public class MessageServiceTest {

    @Container
    private static final RedisContainer REDIS_CONTAINER = TestcontainersUtil.getRedisContainer();

    @Autowired
    private SessionService sessionService;

    @Autowired
    private MessageService messageService;

    @Value("${local.server.port}")
    private int port;

    private GameSession gameSession;
    private StompSession stompSession;
    private CompletableFuture<String> completableFuture;
    private static final Gson gson = new Gson();

    @DynamicPropertySource
    private static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.host", REDIS_CONTAINER::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS_CONTAINER.getMappedPort(6379).toString());
    }

    @DynamicPropertySource
    static void registerKafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("sockbowl.kafka.bootstrap-servers", () -> "localhost:9092");
    }


    @BeforeEach
    void beforeEach() throws InterruptedException, ExecutionException, TimeoutException {

        // Create a game
        CreateGameRequest createGameRequest = new CreateGameRequest();

        gameSession = sessionService.createNewGame(createGameRequest);

        // Setup web socket client
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(createTransportClient()));
        stompClient.setMessageConverter(new GsonMessageConverterWithStringResponse());
        String stompUrl = "ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT;

        // Create join game request
        JoinGameRequest joinGameRequest = new JoinGameRequest();
        joinGameRequest.setJoinCode(gameSession.getJoinCode());
        joinGameRequest.setPlayerSessionId(UUID.randomUUID().toString());
        joinGameRequest.setName("Jimmy");

        // Create another join game request
        JoinGameRequest joinGameRequest2 = new JoinGameRequest();
        joinGameRequest2.setJoinCode(gameSession.getJoinCode());
        joinGameRequest2.setPlayerSessionId(UUID.randomUUID().toString());
        joinGameRequest2.setName("James");
        	
        // Add player to game
        sessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);
        sessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest2);

        // Get updated game session
        gameSession = sessionService.getGameSessionById(gameSession.getId());

        // Create new completable future
        completableFuture = new CompletableFuture<>();

        // Create headers
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("playerId", gameSession.getPlayerList().get(0).getPlayerId());
        headers.add("playerSessionSecret", gameSession.getPlayerList().get(0).getPlayerSecret());
        headers.add("gameSessionId", gameSession.getId());

        // Create STOMP sessions
        stompSession = stompClient.connectAsync(stompUrl, headers, new StompSessionHandlerAdapter(){}).get(600, SECONDS);

        // Get updated game session
        gameSession = sessionService.getGameSessionById(gameSession.getId());
    }


    @Test
    void givenPlayerId_whenSubscribed_thenShouldReceiveExpectedMessage() throws ExecutionException, InterruptedException,
            TimeoutException {

        // Subscribe to response endpoint
        stompSession.subscribe( "/" + MessageQueues.GAME_EVENT_QUEUE + "/" + gameSession.getId() + "/" +
                        gameSession.getPlayerList().get(0).getPlayerId(),
                new WebSocketUtils.SimpleStompFrameHandler(completableFuture));

        // Construct the UpdatePlayerTeamMessage
        UpdatePlayerTeam updatePlayerTeam = UpdatePlayerTeam.builder()
                .targetPlayer(gameSession.getPlayerList().get(1).getPlayerId())
                .targetTeam("FAKE_TEAM_ID")
                .gameSessionId(gameSession.getId())
                .originatingPlayerId(gameSession.getPlayerList().get(0).getPlayerId())
                .build();

        messageService.sendMessage(updatePlayerTeam);

        // Wait for value
        String response = completableFuture.get(10, SECONDS);

        // Validate that it is of type ProcessErrorMessage
        Assertions.assertDoesNotThrow(() -> gson.fromJson(response, ProcessError.class));

        // Convert to object
        ProcessError processError = gson.fromJson(response, ProcessError.class);

        // Assert values are right
        assertEquals(MessageTypes.ERROR, processError.getMessageType());
        assertEquals("Target team or player does not exist", processError.getError());
    }

}
