package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.config.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.config.JoinStatus;
import com.soulsoftworks.sockbowlgame.model.game.config.PlayerMode;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class GameSessionServiceTest {

    @Autowired
    private GameSessionService gameSessionService;



    static {
        GenericContainer<?> redisContainer =
                new GenericContainer<>(DockerImageName.parse("redislabs/redisearch:latest"))
                        .withExposedPorts(6379);
        redisContainer.start();
        System.setProperty("redis-game-cache.host", redisContainer.getHost());
        System.setProperty("redis-game-cache.port", redisContainer.getMappedPort(6379).toString());
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