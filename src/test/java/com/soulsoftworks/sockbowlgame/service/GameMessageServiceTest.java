package com.soulsoftworks.sockbowlgame.service;

import com.google.gson.Gson;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.TestcontainersUtil;
import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import com.soulsoftworks.sockbowlgame.controller.helper.GsonMessageConverterWithStringResponse;
import com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.Player;
import com.soulsoftworks.sockbowlgame.model.game.state.PlayerMode;
import com.soulsoftworks.sockbowlgame.model.game.socket.MessageQueues;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.ProcessError;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.PlayerIdentifiers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils.createTransportClient;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
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

    @Test
    public void givenValidGameSession_whenSendingGameStateToPlayer_thenReturnsCorrectGameSession() throws Exception {
        Player player = gameSession.getPlayerList().get(0);
        String playerSecret = player.getPlayerSecret();

        GameSessionInjection gameSessionInjection = new GameSessionInjection(new PlayerIdentifiers(
                stompSession.getSessionId(), playerSecret), gameSession.getId(), gameSession);

        stompSession.subscribe("/user/" + player.getPlayerId() + "/" + MessageQueues.GAME_STATE_QUEUE,
                new WebSocketUtils.SimpleStompFrameHandler(completableFuture));

        gameMessageService.sendGameStateToPlayer(gameSessionInjection);

        String response = completableFuture.get(10, TimeUnit.SECONDS);
        GameSession responseGameSession = gson.fromJson(response, GameSession.class);

        // Assert GameSession fields
        assertEquals(gameSession.getId(), responseGameSession.getId());
        assertEquals(gameSession.getJoinCode(), responseGameSession.getJoinCode());

        // Assert GameSettings fields
        assertEquals(gameSession.getGameSettings().getProctorType(), responseGameSession.getGameSettings().getProctorType());
        assertEquals(gameSession.getGameSettings().getGameMode(), responseGameSession.getGameSettings().getGameMode());
        assertEquals(gameSession.getGameSettings().getNumPlayers(), responseGameSession.getGameSettings().getNumPlayers());
        assertEquals(gameSession.getGameSettings().getNumTeams(), responseGameSession.getGameSettings().getNumTeams());

        // Assert Player fields
        assertEquals(gameSession.getPlayerList().size(), responseGameSession.getPlayerList().size());
        for (int i = 0; i < gameSession.getPlayerList().size(); i++) {
            Player originalPlayer = gameSession.getPlayerList().get(i);
            Player responsePlayer = responseGameSession.getPlayerList().get(i);
            assertEquals(originalPlayer.getPlayerId(), responsePlayer.getPlayerId());
            assertEquals(originalPlayer.getPlayerMode(), responsePlayer.getPlayerMode());
            assertEquals(originalPlayer.getPlayerSecret(), responsePlayer.getPlayerSecret());
            assertEquals(originalPlayer.getName(), responsePlayer.getName());
        }
    }

    @Test
    public void givenInvalidGameSession_whenSendingGameStateToPlayer_thenReturnsProcessError() throws Exception {
        Player player = gameSession.getPlayerList().get(0);
        String playerSecret = player.getPlayerSecret();

        GameSessionInjection gameSessionInjection = new GameSessionInjection(new PlayerIdentifiers(stompSession.getSessionId(), playerSecret),
                "InvalidGameSessionId", null);

        stompSession.subscribe("/user/" + player.getPlayerId() + "/" + MessageQueues.GAME_STATE_QUEUE,
                new WebSocketUtils.SimpleStompFrameHandler(completableFuture));

        gameMessageService.sendGameStateToPlayer(gameSessionInjection);

        String response = completableFuture.get(10, TimeUnit.SECONDS);
        ProcessError responseError = gson.fromJson(response, ProcessError.class);

        assertEquals("Game session not found.", responseError.getError());
    }



}