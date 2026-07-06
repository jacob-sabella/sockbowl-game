package com.soulsoftworks.sockbowlgame.client;

import com.soulsoftworks.sockbowlgame.config.SockbowlQuestionsConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link PacketClient} attaches (or omits) the Authorization header
 * based on {@link QuestionsTokenProvider}, without needing a real Keycloak server.
 *
 * - When the token provider returns null (guest mode / auth disabled), no
 *   Authorization header should reach sockbowl-questions.
 * - When the token provider returns a token (auth enabled), the request should
 *   carry a "Bearer <token>" Authorization header.
 */
class PacketClientAuthTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private PacketClient buildClient(String tokenOrNull) {
        SockbowlQuestionsConfig config = new SockbowlQuestionsConfig();
        config.setUrl(server.url("/").toString());

        QuestionsTokenProvider tokenProvider = mock(QuestionsTokenProvider.class);
        when(tokenProvider.getTokenOrNull()).thenReturn(tokenOrNull);
        return new PacketClient(config, tokenProvider);
    }

    private static final String GRAPHQL_RESPONSE_BODY =
            "{\"data\":{\"getPacketById\":{\"id\":\"1\",\"name\":\"x\",\"difficulty\":null,\"tossups\":[],\"bonuses\":[]}}}";

    @Test
    void noTokenMeansNoAuthorizationHeader() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(GRAPHQL_RESPONSE_BODY)
                .build());

        PacketClient client = buildClient(null);

        Packet packet = client.getPacketById("1").block(Duration.ofSeconds(5));

        assertThat(packet).isNotNull();
        assertThat(packet.getId()).isEqualTo("1");

        RecordedRequest request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getHeaders().get("Authorization")).isNull();
    }

    @Test
    void tokenPresentMeansBearerAuthorizationHeader() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(GRAPHQL_RESPONSE_BODY)
                .build());

        PacketClient client = buildClient("test-token");

        Packet packet = client.getPacketById("1").block(Duration.ofSeconds(5));

        assertThat(packet).isNotNull();
        assertThat(packet.getId()).isEqualTo("1");

        RecordedRequest request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer test-token");
    }
}
