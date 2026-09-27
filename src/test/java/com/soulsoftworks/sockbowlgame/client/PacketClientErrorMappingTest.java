package com.soulsoftworks.sockbowlgame.client;

import com.soulsoftworks.sockbowlgame.client.QuestionsUnavailableException.Reason;
import com.soulsoftworks.sockbowlgame.config.SockbowlQuestionsConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PacketClient}'s typed failures (AUTH-18) and its mapping of
 * {@code visibility} and {@code owner.id} onto the models {@link Packet}
 * (plan m2-auth WP-G4, {@code PacketClientErrorMappingTest}).
 */
class PacketClientErrorMappingTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);

    private MockWebServer server;
    private QuestionsTokenProvider tokenProvider;
    private PacketClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        SockbowlQuestionsConfig config = new SockbowlQuestionsConfig();
        config.setUrl(server.url("/").toString());
        config.setTimeout(SHORT_TIMEOUT);
        tokenProvider = mock(QuestionsTokenProvider.class);
        when(tokenProvider.getTokenOrNull()).thenReturn("svc-token");
        client = new PacketClient(config, tokenProvider);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private void enqueueJson(int code, String body) {
        server.enqueue(new MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build());
    }

    private Packet fetch() {
        return client.getPacketById("P1").block(Duration.ofSeconds(10));
    }

    private static String packetJson(String visibility, String ownerJson) {
        return """
                {"data":{"getPacketById":{"id":"P1","name":"Packet One",%s"owner":%s,
                "difficulty":{"id":"D1","name":"Regionals"},
                "tossups":[
                  {"id":12,"order":2,"tossup":{"id":"t2","question":"q2","answer":"a2","subcategory":null}},
                  {"id":11,"order":1,"tossup":{"id":"t1","question":"q1","answer":"a1","subcategory":null}}],
                "bonuses":[
                  {"id":21,"order":1,"bonus":{"id":"b1","preamble":"pre","subcategory":null,
                   "bonusParts":[{"id":31,"order":0,"bonusPart":{"id":"bp1","question":"bq","answer":"ba"}}]}}]
                }}}""".formatted(visibility == null ? "\"visibility\":null," : "\"visibility\":\"" + visibility + "\",",
                ownerJson);
    }

    @Test
    void mapsVisibilityOwnerAndContent() throws InterruptedException {
        enqueueJson(200, packetJson("DRAFT", "{\"id\":\"kc-owner\"}"));

        Packet packet = fetch();

        assertThat(packet.getId()).isEqualTo("P1");
        assertThat(packet.getName()).isEqualTo("Packet One");
        assertThat(packet.getVisibility()).isEqualTo(PacketVisibility.DRAFT);
        assertThat(packet.getOwnerId()).isEqualTo("kc-owner");
        assertThat(packet.getDifficulty().getName()).isEqualTo("Regionals");
        assertThat(packet.getTossups()).hasSize(2);
        assertThat(packet.getTossups().get(1).getTossup().getAnswer()).isEqualTo("a1");
        assertThat(packet.getBonuses()).hasSize(1);
        assertThat(packet.getBonuses().get(0).getBonus().getBonusParts().get(0).getBonusPart().getAnswer())
                .isEqualTo("ba");
        // SetMatchPacket sorts the tossups in place.
        packet.getTossups().sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        assertThat(packet.getTossups().get(0).getId()).isEqualTo(11L);

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer svc-token");
        assertThat(request.getBody().utf8()).contains("visibility").contains("owner");
    }

    @Test
    void publishedPacketWithoutOwner() {
        enqueueJson(200, packetJson("PUBLISHED", "null"));

        Packet packet = fetch();

        assertThat(packet.getVisibility()).isEqualTo(PacketVisibility.PUBLISHED);
        assertThat(packet.getOwnerId()).isNull();
    }

    @Test
    void legacyPacketWithoutVisibilityStaysNull() {
        enqueueJson(200, packetJson(null, "null"));

        assertThat(fetch().getVisibility()).isNull();
    }

    @Test
    void ephemeralIsMappedWhenKnownAndOtherwiseFailsClosedToDraft() {
        enqueueJson(200, packetJson("EPHEMERAL", "null"));

        boolean jarKnowsEphemeral = Arrays.stream(PacketVisibility.values()).anyMatch(v -> v.name().equals("EPHEMERAL"));
        assertThat(fetch().getVisibility().name()).isEqualTo(jarKnowsEphemeral ? "EPHEMERAL" : "DRAFT");
    }

    @Test
    void unknownVisibilityFailsClosedToDraft() {
        enqueueJson(200, packetJson("SOMETHING_NEW", "null"));

        assertThat(fetch().getVisibility()).isEqualTo(PacketVisibility.DRAFT);
    }

    @Test
    void nullDataMapsToNotFound() {
        enqueueJson(200, "{\"data\":{\"getPacketById\":null}}");

        assertThatThrownBy(this::fetch)
                .isInstanceOf(PacketNotFoundException.class)
                .extracting(e -> ((PacketNotFoundException) e).getPacketId()).isEqualTo("P1");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void httpAuthFailureMapsToAuthAndDropsTheCachedToken(int status) {
        enqueueJson(status, "{}");

        assertThatThrownBy(this::fetch)
                .isInstanceOf(QuestionsUnavailableException.class)
                .extracting(e -> ((QuestionsUnavailableException) e).getReason()).isEqualTo(Reason.AUTH);
        verify(tokenProvider).invalidate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAUTHORIZED", "FORBIDDEN"})
    void graphQlAuthErrorMapsToAuth(String classification) {
        enqueueJson(200, """
                {"errors":[{"message":"Access denied","path":["getPacketById"],
                "extensions":{"classification":"%s"}}],"data":{"getPacketById":null}}""".formatted(classification));

        assertThatThrownBy(this::fetch)
                .isInstanceOf(QuestionsUnavailableException.class)
                .extracting(e -> ((QuestionsUnavailableException) e).getReason()).isEqualTo(Reason.AUTH);
    }

    @Test
    void otherGraphQlErrorMapsToBadResponse() {
        enqueueJson(200, """
                {"errors":[{"message":"boom","path":["getPacketById"],
                "extensions":{"classification":"INTERNAL_ERROR"}}],"data":{"getPacketById":null}}""");

        assertThatThrownBy(this::fetch)
                .isInstanceOf(QuestionsUnavailableException.class)
                .extracting(e -> ((QuestionsUnavailableException) e).getReason()).isEqualTo(Reason.BAD_RESPONSE);
    }

    @Test
    void serverErrorMapsToUnavailable() {
        enqueueJson(503, "{}");

        assertThatThrownBy(this::fetch)
                .isInstanceOf(QuestionsUnavailableException.class)
                .extracting(e -> ((QuestionsUnavailableException) e).getReason()).isEqualTo(Reason.UNAVAILABLE);
    }

    @Test
    void hangPastTheTimeoutMapsToTimeout() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(packetJson("PUBLISHED", "null"))
                .headersDelay(5, TimeUnit.SECONDS)
                .build());

        long start = System.nanoTime();
        assertThatThrownBy(this::fetch)
                .isInstanceOf(QuestionsUnavailableException.class)
                .extracting(e -> ((QuestionsUnavailableException) e).getReason()).isEqualTo(Reason.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void unreachableServiceMapsToUnavailable() throws IOException {
        server.close();

        assertThatThrownBy(this::fetch)
                .isInstanceOf(QuestionsUnavailableException.class)
                .extracting(e -> ((QuestionsUnavailableException) e).getReason()).isEqualTo(Reason.UNAVAILABLE);
    }

    @Test
    void tokenFailureIsPassedThroughWithoutCallingQuestions() {
        when(tokenProvider.getTokenOrNull())
                .thenThrow(new QuestionsUnavailableException(Reason.TOKEN, "keycloak down"));

        assertThatThrownBy(this::fetch)
                .isInstanceOf(QuestionsUnavailableException.class)
                .extracting(e -> ((QuestionsUnavailableException) e).getReason()).isEqualTo(Reason.TOKEN);
        assertThat(server.getRequestCount()).isZero();
        verify(tokenProvider, never()).invalidate();
    }
}
