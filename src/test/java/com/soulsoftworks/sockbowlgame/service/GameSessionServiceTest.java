package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.GameSession;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.service.GameSessionService;
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
}