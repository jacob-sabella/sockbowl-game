package com.soulsoftworks.sockbowlgame.service;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.GetGameState;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.TimeoutRound;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.MatchState;
import com.soulsoftworks.sockbowlgame.model.state.ProctorType;
import com.soulsoftworks.sockbowlgame.model.state.Round;
import com.soulsoftworks.sockbowlgame.model.state.RoundState;
import com.soulsoftworks.sockbowlgame.model.state.TimerSettings;
import com.soulsoftworks.sockbowlgame.service.processor.ConfigurationMessageProcessor;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * M2R2-LIVE-01 (PLAYER_NOT_IN_SESSION in full-match): every writer of a
 * {@link GameSession} (REST join, the Kafka message processor and the timer
 * tick) does a whole-document read-modify-write against Redis. Without
 * per-session serialization, a writer that loaded the session before a join
 * saved it writes its stale copy back afterwards and silently drops the joined
 * player; that player's next STOMP message is then rejected with
 * PLAYER_NOT_IN_SESSION.
 *
 * <p>Each test pins the interleaving deterministically: the "slow" writer is
 * parked between its load and its save (inside the processor, or inside the
 * timer's auto-timeout send), a REST join is started, and the slow writer is
 * released only once the join has either finished (which is the lost-update
 * interleaving) or is provably queued on the session's lock. The final Redis
 * state must contain every joined player.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sockbowl.auth.enabled=false")
class SessionLostUpdateIT {

    @Container
    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired
    SessionService sessionService;

    @Autowired
    MessageService messageService;

    @Autowired
    GameTimerService gameTimerService;

    @MockitoBean
    KafkaTemplate<String, SockbowlInMessage> kafkaTemplate;

    @MockitoSpyBean
    ConfigurationMessageProcessor configurationMessageProcessor;

    private final CountDownLatch slowWriterLoaded = new CountDownLatch(1);
    private final CountDownLatch releaseSlowWriter = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @BeforeEach
    void kafkaSendsAreNoOps() {
        when(kafkaTemplate.send(anyString(), any(SockbowlInMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void cleanup() {
        releaseSlowWriter.countDown();
        pool.shutdownNow();
    }

    @Test
    @DisplayName("A join that lands while a Kafka message is being processed is not overwritten by the processor's save")
    void joinDuringProcessedMessageIsNotLost() throws Exception {
        GameSession game = newGame(GameMode.QUIZ_BOWL_CLASSIC);
        JoinGameResponse proctor = join(game, "Proctor");
        JoinGameResponse first = join(game, "Ada");

        // Park the processor right after MessageService loaded the session and
        // before it saves it: exactly what a bot's on-connect get-game does.
        AtomicBoolean parked = new AtomicBoolean();
        doAnswer(inv -> {
            if (parked.compareAndSet(false, true)) {
                slowWriterLoaded.countDown();
                assertThat(releaseSlowWriter.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return inv.callRealMethod();
        }).when(configurationMessageProcessor).processMessage(any());

        GetGameState getGame = GetGameState.builder()
                .gameSessionId(game.getId())
                .originatingPlayerId(first.getPlayerSessionId())
                .build();
        Future<?> processing = pool.submit(() ->
                messageService.processGameMessage(new ConsumerRecord<>("game-session-topic", 0, 0L, null, getGame)));
        assertThat(slowWriterLoaded.await(10, TimeUnit.SECONDS)).isTrue();

        // The next bot joins over REST while the processor still holds its copy.
        JoinThread second = JoinThread.start(() -> join(game, "Blaise"));
        awaitJoinDoneOrQueued(second, game.getId());
        assertThat(second.thread.isAlive()).as("the join waits for the in-flight writer").isTrue();
        releaseSlowWriter.countDown();

        processing.get(20, TimeUnit.SECONDS);
        JoinGameResponse secondJoin = second.result();

        assertThat(playerIds(game.getId())).contains(
                proctor.getPlayerSessionId(), first.getPlayerSessionId(), secondJoin.getPlayerSessionId());
    }

    @Test
    @DisplayName("A join that lands during a timer tick is not overwritten by the tick's save")
    void joinDuringTimerTickIsNotLost() throws Exception {
        GameSession game = newGame(GameMode.QUIZ_BOWL_CLASSIC);
        JoinGameResponse proctor = join(game, "Proctor");

        // Put the session in-game with a tossup timer that expires on the next
        // tick, so the tick sends an auto-timeout (a point we can park it at)
        // between loading the session and saving it.
        GameSession stored = sessionService.getGameSessionById(game.getId());
        stored.getCurrentMatch().setMatchState(MatchState.IN_GAME);
        Round round = stored.getCurrentRound();
        round.setRoundState(RoundState.AWAITING_BUZZ);
        round.startTossupTimer(1);

        AtomicBoolean parked = new AtomicBoolean();
        when(kafkaTemplate.send(anyString(), any(SockbowlInMessage.class))).thenAnswer(inv -> {
            SockbowlInMessage sent = inv.getArgument(1);
            if (sent instanceof TimeoutRound && game.getId().equals(sent.getGameSessionId())
                    && parked.compareAndSet(false, true)) {
                slowWriterLoaded.countDown();
                assertThat(releaseSlowWriter.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return CompletableFuture.completedFuture(null);
        });
        sessionService.saveGameSession(stored);

        // Either the scheduled tick or this explicit one gets parked first.
        Future<?> tick = pool.submit(() -> gameTimerService.processTimers());
        assertThat(slowWriterLoaded.await(10, TimeUnit.SECONDS)).isTrue();

        JoinThread late = JoinThread.start(() -> join(game, "Late"));
        awaitJoinDoneOrQueued(late, game.getId());
        assertThat(late.thread.isAlive()).as("the join waits for the in-flight writer").isTrue();
        releaseSlowWriter.countDown();

        tick.get(20, TimeUnit.SECONDS);
        JoinGameResponse lateJoin = late.result();

        assertThat(playerIds(game.getId()))
                .contains(proctor.getPlayerSessionId(), lateJoin.getPlayerSessionId());
    }

    @Test
    @DisplayName("Many concurrent joins interleaved with processed messages drop no player")
    void concurrentJoinsAndMessagesDropNoPlayer() throws Exception {
        GameSession game = newGame(GameMode.FREE_FOR_ALL);
        JoinGameResponse host = join(game, "Host");

        int joiners = 7; // FREE_FOR_ALL seats at most 8 buzzers, host included
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JoinGameResponse>> joins = new ArrayList<>();
        List<Future<?>> messages = new ArrayList<>();
        for (int i = 0; i < joiners; i++) {
            String name = "P" + i;
            joins.add(pool.submit(() -> {
                start.await();
                return join(game, name);
            }));
            messages.add(pool.submit(() -> {
                start.await();
                for (int k = 0; k < 5; k++) {
                    GetGameState getGame = GetGameState.builder()
                            .gameSessionId(game.getId())
                            .originatingPlayerId(host.getPlayerSessionId())
                            .build();
                    messageService.processGameMessage(
                            new ConsumerRecord<>("game-session-topic", 0, 0L, null, getGame));
                }
                return null;
            }));
        }
        start.countDown();

        List<String> expected = new ArrayList<>(List.of(host.getPlayerSessionId()));
        for (Future<JoinGameResponse> f : joins) {
            expected.add(f.get(30, TimeUnit.SECONDS).getPlayerSessionId());
        }
        for (Future<?> f : messages) {
            f.get(30, TimeUnit.SECONDS);
        }

        GameSession finalState = sessionService.getGameSessionById(game.getId());
        assertThat(playerIds(game.getId())).containsExactlyInAnyOrderElementsOf(expected);
        // FREE_FOR_ALL seats each joiner on their own team; none of those may be lost either.
        assertThat(finalState.getTeamList()).hasSize(expected.size());
    }

    /**
     * Wait until the concurrent join has either completed (it did not wait for
     * the slow writer: the lost-update interleaving) or is itself parked on
     * the session's lock (the serialized interleaving). No sleeps: both
     * outcomes are observed directly.
     */
    private void awaitJoinDoneOrQueued(JoinThread join, String gameSessionId) {
        await().atMost(15, TimeUnit.SECONDS).pollInterval(5, TimeUnit.MILLISECONDS)
                .until(() -> !join.thread.isAlive() || GameSessionLocks.isQueued(gameSessionId, join.thread));
    }

    /** A join on its own thread, so the test can ask whether that thread is queued on the lock. */
    private static final class JoinThread {
        final Thread thread;
        final AtomicReference<JoinGameResponse> response = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        private JoinThread(Callable<JoinGameResponse> join) {
            thread = new Thread(() -> {
                try {
                    response.set(join.call());
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "concurrent-join");
        }

        static JoinThread start(Callable<JoinGameResponse> join) {
            JoinThread j = new JoinThread(join);
            j.thread.start();
            return j;
        }

        JoinGameResponse result() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(thread.isAlive()).as("join finished").isFalse();
            assertThat(failure.get()).as("join failure").isNull();
            return response.get();
        }
    }

    private List<String> playerIds(String gameSessionId) {
        return sessionService.getGameSessionById(gameSessionId).getPlayerList().stream()
                .map(p -> p.getPlayerId()).toList();
    }

    private GameSession newGame(GameMode mode) {
        GameSettings settings = new GameSettings();
        settings.setGameMode(mode);
        settings.setProctorType(ProctorType.IN_PERSON_PROCTOR);
        settings.setTimerSettings(TimerSettings.builder().autoTimerEnabled(true).build());
        CreateGameRequest request = new CreateGameRequest();
        request.setGameSettings(settings);
        return sessionService.createNewGame(request);
    }

    private JoinGameResponse join(GameSession game, String name) {
        return sessionService.addPlayerToGameSessionWithJoinCode(
                JoinGameRequest.builder().joinCode(game.getJoinCode()).name(name).build());
    }
}
