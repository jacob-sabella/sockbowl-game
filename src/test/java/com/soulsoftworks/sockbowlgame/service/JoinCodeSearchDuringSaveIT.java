package com.soulsoftworks.sockbowlgame.service;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spurious 404 "Game not found" on join seen in full-match: on the Redis
 * that compose ships, a RediSearch query by join code can come back empty
 * while the same session is being saved (a direct probe through the
 * repository missed 442 of 5000 lookups with one concurrent saver). Join-code
 * lookups through {@link SessionService} must never miss because of a save
 * made by this process.
 *
 * <p>This runs against the compose Redis version (redis:8.10.2), not
 * {@code TestcontainersUtil}'s redis:8.2, on which the miss does not occur.
 */
@Testcontainers
@SpringBootTest(properties = "sockbowl.auth.enabled=false")
class JoinCodeSearchDuringSaveIT {

    @Container
    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:8.10.2")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired
    SessionService sessionService;

    @MockitoBean
    KafkaTemplate<String, SockbowlInMessage> kafkaTemplate;

    @Test
    @DisplayName("Join-code lookups find the session while it is being saved concurrently")
    void joinCodeLookupNeverMissesDuringSaves() throws Exception {
        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.QUIZ_BOWL_CLASSIC);
        CreateGameRequest request = new CreateGameRequest();
        request.setGameSettings(settings);
        GameSession game = sessionService.createNewGame(request);
        for (int i = 0; i < 4; i++) {
            sessionService.addPlayerToGameSessionWithJoinCode(
                    JoinGameRequest.builder().joinCode(game.getJoinCode()).name("P" + i).build());
        }

        // A writer that keeps rewriting the session, the way the processor and
        // the timer do.
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                while (!stop.get()) {
                    GameSessionLocks.withLock(game.getId(), () ->
                            sessionService.saveGameSession(sessionService.getGameSessionById(game.getId())));
                }
            } catch (Throwable t) {
                writerFailure.set(t);
            }
        }, "session-writer");
        writer.start();

        int lookups = 3000;
        int misses = 0;
        try {
            for (int i = 0; i < lookups; i++) {
                if (sessionService.getGameSessionByJoinCode(game.getJoinCode()) == null) {
                    misses++;
                }
            }
        } finally {
            stop.set(true);
            writer.join();
        }

        assertThat(writerFailure.get()).isNull();
        assertThat(misses).as("join-code lookups that missed out of %d", lookups).isZero();
    }
}
