package com.soulsoftworks.sockbowlgame.security.stomp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.controller.helper.StompTestClient;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.ProctorType;
import com.soulsoftworks.sockbowlgame.service.MessageService;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Shared plumbing for the STOMP security ITs: a Redis Testcontainer for game
 * state, and a Kafka loopback. Instead of a broker, the {@link KafkaTemplate}
 * is replaced by a stub that runs each produced message through the same JSON
 * serializer/deserializer pair as {@code KafkaConfig} and hands it straight to
 * {@link MessageService#processGameMessage}, so the full
 * STOMP -> controller -> "Kafka" -> processor -> broker path runs synchronously.
 */
@Testcontainers
abstract class StompSecurityITSupport {

    @Container
    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    SessionService sessionService;

    @Autowired
    MessageService messageService;

    @Autowired
    SimpUserRegistry userRegistry;

    @MockitoBean
    KafkaTemplate<String, SockbowlInMessage> kafkaTemplate;

    StompTestClient client;
    private final List<StompTestClient.Connection> connections = new ArrayList<>();

    @BeforeEach
    void kafkaLoopbackAndClient() {
        when(kafkaTemplate.send(anyString(), any(SockbowlInMessage.class))).thenAnswer(inv -> {
            String topic = inv.getArgument(0);
            SockbowlInMessage consumed = kafkaRoundTrip(topic, inv.getArgument(1));
            messageService.processGameMessage(new ConsumerRecord<>(topic, 0, 0L, null, consumed));
            return CompletableFuture.completedFuture(null);
        });
        client = new StompTestClient(port);
    }

    @AfterEach
    void closeConnections() {
        connections.forEach(StompTestClient.Connection::disconnect);
        client.stop();
    }

    static SockbowlInMessage kafkaRoundTrip(String topic, SockbowlInMessage message) {
        RecordHeaders headers = new RecordHeaders();
        try (JacksonJsonSerializer<SockbowlInMessage> serializer = new JacksonJsonSerializer<>();
             JacksonJsonDeserializer<SockbowlInMessage> deserializer =
                     new JacksonJsonDeserializer<>(SockbowlInMessage.class)) {
            deserializer.addTrustedPackages("com.soulsoftworks.sockbowlgame");
            return deserializer.deserialize(topic, headers, serializer.serialize(topic, headers, message));
        }
    }

    StompTestClient.Connection connect(String gameSessionId, String playerSessionId, String secret, String token) {
        StompTestClient.Connection connection = client.connect(
                StompTestClient.connectHeaders(gameSessionId, playerSessionId, secret, token));
        connections.add(connection);
        return connection;
    }

    GameSession newGame() {
        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.QUIZ_BOWL_CLASSIC);
        settings.setProctorType(ProctorType.IN_PERSON_PROCTOR);
        CreateGameRequest request = new CreateGameRequest();
        request.setGameSettings(settings);
        return sessionService.createNewGame(request);
    }

    JoinGameResponse joinAsGuest(GameSession game, String name) {
        return sessionService.addPlayerToGameSessionWithJoinCode(
                JoinGameRequest.builder().joinCode(game.getJoinCode()).name(name).build());
    }

    /** Wait until the broker has registered {@code count} subscriptions to {@code destination}. */
    void awaitSubscribed(String destination, int count) {
        await().atMost(10, TimeUnit.SECONDS).until(() ->
                userRegistry.findSubscriptions(s -> destination.equals(s.getDestination())).size() >= count);
    }

    /**
     * SEND until a reply shows up on {@code replies}. SUBSCRIBE and SEND frames
     * are dispatched on different executor threads, so the first request can
     * race the broker's registration of a just-made subscription.
     */
    static String requestUntilReply(StompTestClient.Connection connection, String destination, String body,
                                    BlockingQueue<String> replies) throws InterruptedException {
        for (int attempt = 0; attempt < 10; attempt++) {
            connection.send(destination, body);
            String reply = replies.poll(1, TimeUnit.SECONDS);
            if (reply != null) {
                return reply;
            }
        }
        throw new AssertionError("no reply to " + destination);
    }

    static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    static JsonObject poll(BlockingQueue<String> queue) throws InterruptedException {
        String raw = queue.poll(10, TimeUnit.SECONDS);
        assertThat(raw).as("expected a message").isNotNull();
        return json(raw);
    }

    /** Asserts a typed ERROR frame arrived and the server closed the socket. */
    static void assertFatal(StompTestClient.Connection connection, StompErrorCode code) throws Exception {
        StompTestClient.ErrorFrame error = connection.awaitError();
        assertThat(error.message()).isEqualTo(code.name());
        assertThat(error.code()).isEqualTo(code.name());
        JsonObject body = json(error.body());
        assertThat(body.get("code").getAsString()).isEqualTo(code.name());
        assertThat(body.has("message")).isTrue();
        assertThat(body.has("retryAfterSeconds")).isTrue();
        assertThat(error.body()).doesNotContain("Exception", "\tat ");
        assertThat(connection.awaitClosed()).as("socket closed after ERROR").isTrue();
    }
}
