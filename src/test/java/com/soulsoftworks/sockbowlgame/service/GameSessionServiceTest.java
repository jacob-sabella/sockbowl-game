package com.soulsoftworks.sockbowlgame.service;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.JoinStatus;
import com.soulsoftworks.sockbowlgame.model.game.state.PlayerMode;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Testcontainers
class GameSessionServiceTest {

    @Autowired
    private GameSessionService gameSessionService;

    @Container
    private static final RedisContainer REDIS_CONTAINER =
            new RedisContainer(DockerImageName.parse("redislabs/redisearch:latest")).withExposedPorts(6379);

    @DynamicPropertySource
    private static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.host", REDIS_CONTAINER::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS_CONTAINER.getMappedPort(6379).toString());
    }

    @Test
    public void createNewGame_returnedValueEqualRedisStoredValue(){
        CreateGameRequest createGameRequest = new CreateGameRequest();

        // Create new session in Redis and get it back from the method call
        GameSession gameSession = gameSessionService.createNewGame(createGameRequest);

        // Get game session out of redis using the ID from createNewGame()
        GameSession gameSessionFromRedis = gameSessionService.getGameSessionById(gameSession.getId());

        // Verify that objects are identical
        assertEquals(gameSession, gameSessionFromRedis);
    }

    @Test
    public void addPlayerToGameSessionWithJoinCode_addingPlayerExistingGameSucceeds(){
        CreateGameRequest createGameRequest = new CreateGameRequest();
        createGameRequest.getGameSettings().setNumPlayers(8);

        // Create new session in Redis and get it back from the method call
        GameSession gameSession = gameSessionService.createNewGame(createGameRequest);

        // Get game session out of redis using the ID from createNewGame()
        GameSession gameSessionFromRedis = gameSessionService.getGameSessionById(gameSession.getId());

        // Create join game request
        JoinGameRequest joinGameRequest = new JoinGameRequest();
        joinGameRequest.setJoinCode(gameSessionFromRedis.getJoinCode());
        joinGameRequest.setPlayerSessionId("FAKE_PLAYER_SESSION_ID");
        joinGameRequest.setPlayerMode(PlayerMode.BUZZER_ONLY);
        joinGameRequest.setName("Jimmy");

        // Join game with service
        JoinGameResponse joinGameResponse = gameSessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);

        assertEquals(JoinStatus.SUCCESS, joinGameResponse.getJoinStatus());
    }

    @Test
    public void addPlayerToGameSessionWithJoinCode_addingTooManyPlayersThrowsSessionFull(){
        CreateGameRequest createGameRequest = new CreateGameRequest();
        createGameRequest.getGameSettings().setNumPlayers(1);

        // Create new session in Redis and get it back from the method call
        GameSession gameSession = gameSessionService.createNewGame(createGameRequest);

        // Get game session out of redis using the ID from createNewGame()
        GameSession gameSessionFromRedis = gameSessionService.getGameSessionById(gameSession.getId());

        // Create join game request
        JoinGameRequest joinGameRequest = new JoinGameRequest();
        joinGameRequest.setJoinCode(gameSessionFromRedis.getJoinCode());
        joinGameRequest.setPlayerSessionId("FAKE_PLAYER_SESSION_ID");
        joinGameRequest.setPlayerMode(PlayerMode.BUZZER_ONLY);
        joinGameRequest.setName("Jimmy");

        // Join game with service. This should succeed because there is only 1 player allowed
        JoinGameResponse joinGameResponse = gameSessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);
        assertEquals(JoinStatus.SUCCESS, joinGameResponse.getJoinStatus());

        // Join game again. This should fail because we maxed out the number of players
        joinGameResponse = gameSessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);
        assertEquals(JoinStatus.SESSION_FULL, joinGameResponse.getJoinStatus());
    }

    @Test
    public void addPlayerToGameSessionWithJoinCode_tryingToJoinGameSessionWithNonExistentCodeThrowsGameNotExistError(){
        CreateGameRequest createGameRequest = new CreateGameRequest();
        createGameRequest.getGameSettings().setNumPlayers(1);

        // Create new session in Redis and get it back from the method call
        GameSession gameSession = gameSessionService.createNewGame(createGameRequest);

        // Get game session out of redis using the ID from createNewGame()
        GameSession gameSessionFromRedis = gameSessionService.getGameSessionById(gameSession.getId());

        // Create join game request with invalid code
        JoinGameRequest joinGameRequest = new JoinGameRequest();
        joinGameRequest.setJoinCode("AAAA");
        joinGameRequest.setPlayerSessionId("FAKE_PLAYER_SESSION_ID");
        joinGameRequest.setPlayerMode(PlayerMode.BUZZER_ONLY);
        joinGameRequest.setName("Jimmy");

        // Join game with service. This should succeed because there is only 1 player allowed
        JoinGameResponse joinGameResponse = gameSessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);
        assertEquals(JoinStatus.GAME_DOES_NOT_EXIST, joinGameResponse.getJoinStatus());
    }

}