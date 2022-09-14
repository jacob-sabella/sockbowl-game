package com.soulsoftworks.sockbowlgame.game.service;

import com.soulsoftworks.sockbowlgame.game.model.GameSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class SessionManagementServiceTest {

    @Autowired
    private SessionManagementService sessionManagementService;

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
        // Create new session in Redis and get it back from the method call
        GameSession gameSession = sessionManagementService.createNewGame();

        // Get game session out of redis using the ID from createNewGame()
        GameSession gameSessionFromRedis = sessionManagementService.getGameSessionById(gameSession.getId());

        // Verify that objects are identical
        assertEquals(gameSession, gameSessionFromRedis);
    }
}