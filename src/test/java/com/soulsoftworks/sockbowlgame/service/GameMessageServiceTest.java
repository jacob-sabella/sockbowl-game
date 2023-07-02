package com.soulsoftworks.sockbowlgame.service;

import com.google.gson.Gson;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.TestcontainersUtil;
import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import com.soulsoftworks.sockbowlgame.controller.helper.GsonMessageConverterWithStringResponse;
import com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils;
import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageQueues;
import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.TestSockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.config.UpdatePlayerTeamMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.error.ProcessErrorMessage;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.PlayerMode;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.*;

import static com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils.createTransportClient;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableKafka
public class GameMessageServiceTest {

    @Container
    private static final RedisContainer REDIS_CONTAINER = TestcontainersUtil.getRedisContainer();

    @Container
    private static final KafkaContainer kafkaContainer = TestcontainersUtil.getKafkaContainer();

    @Autowired
    private GameSessionService gameSessionService;

    @Autowired
    private GameMessageService gameMessageService;

    @Value("${local.server.port}")
    private int port;

    private GameSession gameSession;
    private StompSession stompSession;
    private CompletableFuture<String> completableFuture;
    private static final Gson gson = new Gson();
    private final BlockingQueue<ConsumerRecord<String, SockbowlInMessage>> records = new LinkedBlockingDeque<>();

    private CountDownLatch latch = new CountDownLatch(1);

    @DynamicPropertySource
    private static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.host", REDIS_CONTAINER::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS_CONTAINER.getMappedPort(6379).toString());
    }

    @DynamicPropertySource
    static void registerKafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
        System.out.println(kafkaContainer.getBootstrapServers());
    }


    @BeforeEach
    void beforeEach() throws InterruptedException, ExecutionException, TimeoutException {
        // Allow some time for the listener to start
        latch.await(10, TimeUnit.SECONDS);

        // Create a game
        CreateGameRequest createGameRequest = new CreateGameRequest();
        createGameRequest.getGameSettings().setNumPlayers(8);
        createGameRequest.getGameSettings().setNumTeams(2);

        gameSession = gameSessionService.createNewGame(createGameRequest);

        // Setup web socket client
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(createTransportClient()));
        stompClient.setMessageConverter(new GsonMessageConverterWithStringResponse());
        String stompUrl = "ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT;

        // Create join game request
        JoinGameRequest joinGameRequest = new JoinGameRequest();
        joinGameRequest.setJoinCode(gameSession.getJoinCode());
        joinGameRequest.setPlayerSessionId(UUID.randomUUID().toString());
        joinGameRequest.setPlayerMode(PlayerMode.BUZZER);
        joinGameRequest.setName("Jimmy");

        // Create another join game request
        JoinGameRequest joinGameRequest2 = new JoinGameRequest();
        joinGameRequest2.setJoinCode(gameSession.getJoinCode());
        joinGameRequest2.setPlayerSessionId(UUID.randomUUID().toString());
        joinGameRequest2.setPlayerMode(PlayerMode.BUZZER);
        joinGameRequest2.setName("James");

        // Add player to game
        gameSessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);
        gameSessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest2);

        // Get updated game session
        gameSession = gameSessionService.getGameSessionById(gameSession.getId());

        // Create new completable future
        completableFuture = new CompletableFuture<>();

        // Create headers
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("playerId", gameSession.getPlayerList().get(0).getPlayerId());
        headers.add("playerSessionSecret", gameSession.getPlayerList().get(0).getPlayerSecret());
        headers.add("gameSessionId", gameSession.getId());

        // Create STOMP sessions
        stompSession = stompClient.connect(stompUrl, headers, new StompSessionHandlerAdapter(){}).get(600, SECONDS);

        // Get updated game session
        gameSession = gameSessionService.getGameSessionById(gameSession.getId());
    }


    @Test
    void givenPlayerId_whenSubscribed_thenShouldReceiveExpectedMessage() throws ExecutionException, InterruptedException,
            TimeoutException {

        // Subscribe to response endpoint
        stompSession.subscribe( "/" + MessageQueues.GAME_EVENT_QUEUE + "/" + gameSession.getId() + "/" +
                        gameSession.getPlayerList().get(0).getPlayerId(),
                new WebSocketUtils.SimpleStompFrameHandler(completableFuture));

        // Construct the UpdatePlayerTeamMessage
        UpdatePlayerTeamMessage updatePlayerTeamMessage = UpdatePlayerTeamMessage.builder()
                .targetPlayer(gameSession.getPlayerList().get(1).getPlayerId())
                .targetTeam("FAKE_TEAM_ID")
                .gameSessionId(gameSession.getId())
                .originatingPlayerId(gameSession.getPlayerList().get(0).getPlayerId())
                .build();

        gameMessageService.sendMessage(updatePlayerTeamMessage);

        // Wait for value
        String response = completableFuture.get(10, SECONDS);

        // Validate that it is of type ProcessErrorMessage
        Assertions.assertDoesNotThrow(() -> gson.fromJson(response, ProcessErrorMessage.class));

        // Convert to object
        ProcessErrorMessage processErrorMessage = gson.fromJson(response, ProcessErrorMessage.class);

        // Assert values are right
        assertEquals(MessageTypes.ERROR, processErrorMessage.getMessageType());
        assertEquals("Target team or player does not exist", processErrorMessage.getError());
    }

    @Test
    void shouldDeliverGenericMessageSuccessfully() throws InterruptedException {

        TestSockbowlInMessage testSockbowlMessage = new TestSockbowlInMessage();

        gameMessageService.sendMessage("game-topic-test", testSockbowlMessage);

        // wait for the message to be delivered
        ConsumerRecord<String, SockbowlInMessage> receivedMessage = records.poll(5, TimeUnit.SECONDS);
        assertNotNull(receivedMessage);
        assertEquals(MessageTypes.GENERIC, receivedMessage.value().getMessageType());
    }

    @KafkaListener(topics = "game-topic-test", groupId = "game-consumers" )
    public void fakeListen(ConsumerRecord<String, SockbowlInMessage> record) {
        records.add(record);
    }

}
