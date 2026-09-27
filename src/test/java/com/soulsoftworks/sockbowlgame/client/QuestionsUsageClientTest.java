package com.soulsoftworks.sockbowlgame.client;

import com.soulsoftworks.sockbowlgame.client.QuestionsUsageClient.ContentCounts;
import com.soulsoftworks.sockbowlgame.config.SockbowlQuestionsConfig;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QuestionsUsageClient} in isolation (plan m4-limits WP-G6): the
 * batched, always-fail-open call to sockbowl-questions' (WP-Q4, coded here
 * strictly against the plan's contract) {@code GET
 * /api/admin/usage/content-counts}.
 */
class QuestionsUsageClientTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);

    private MockWebServer server;
    private QuestionsUsageClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        SockbowlQuestionsConfig config = new SockbowlQuestionsConfig();
        config.setUrl(server.url("/").toString());
        config.setTimeout(SHORT_TIMEOUT);
        client = new QuestionsUsageClient(config);
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

    @Test
    void emptySubsIsANoOpWithoutAnyCall() {
        assertThat(client.contentCounts(List.of(), "tok")).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void parsesContentCountsAndRelaysTheBearerToken() throws InterruptedException {
        enqueueJson(200, "{\"kc-a\":{\"packetsOwned\":7,\"questionsCreated\":3},"
                + "\"kc-b\":{\"packetsOwned\":0,\"questionsCreated\":0}}");

        Map<String, ContentCounts> result = client.contentCounts(List.of("kc-a", "kc-b"), "admin-token");

        assertThat(result).containsEntry("kc-a", new ContentCounts(7, 3));
        assertThat(result).containsEntry("kc-b", new ContentCounts(0, 0));

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer admin-token");
        assertThat(request.getTarget()).contains("content-counts").contains("kc-a").contains("kc-b");
    }

    @Test
    void chunksAtOneHundredSubsPerCall() throws InterruptedException {
        List<String> subs = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            subs.add("kc-" + i);
        }
        enqueueJson(200, oneCountEach(subs.subList(0, 100)));
        enqueueJson(200, oneCountEach(subs.subList(100, 150)));

        Map<String, ContentCounts> result = client.contentCounts(subs, "tok");

        assertThat(result).hasSize(150);
        assertThat(result.get("kc-0")).isEqualTo(new ContentCounts(1, 1));
        assertThat(result.get("kc-149")).isEqualTo(new ContentCounts(1, 1));
        assertThat(server.getRequestCount()).isEqualTo(2);

        RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest second = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(first.getTarget()).contains("kc-99").doesNotContain("kc-100");
        assertThat(second.getTarget()).contains("kc-100").contains("kc-149");
    }

    private static String oneCountEach(List<String> subs) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < subs.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("\"").append(subs.get(i)).append("\":{\"packetsOwned\":1,\"questionsCreated\":1}");
        }
        return json.append("}").toString();
    }

    @Test
    void aChunkFailureFailsTheWholeBatchOpen() {
        enqueueJson(200, oneCountEach(List.of("kc-0")));
        server.enqueue(new MockResponse.Builder().code(503).body("down").build());
        List<String> subs = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            subs.add("kc-" + i);
        }

        assertThat(client.contentCounts(subs, "tok")).isNull();
    }

    @Test
    void nonTwoXxRespondsWithNull() {
        enqueueJson(503, "{}");

        assertThat(client.contentCounts(List.of("kc-a"), "tok")).isNull();
    }

    @Test
    void unparsableBodyRespondsWithNull() {
        enqueueJson(200, "not json");

        assertThat(client.contentCounts(List.of("kc-a"), "tok")).isNull();
    }

    @Test
    void blankBodyIsAnEmptyMap() {
        enqueueJson(200, "");

        assertThat(client.contentCounts(List.of("kc-a"), "tok")).isEmpty();
    }

    @Test
    void unreachableServiceRespondsWithNull() throws IOException {
        server.close();

        assertThat(client.contentCounts(List.of("kc-a"), "tok")).isNull();
    }

    @Test
    void hangPastTheTimeoutRespondsWithNull() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(oneCountEach(List.of("kc-a")))
                .headersDelay(5, TimeUnit.SECONDS)
                .build());

        long start = System.nanoTime();
        assertThat(client.contentCounts(List.of("kc-a"), "tok")).isNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }
}
