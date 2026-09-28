package com.soulsoftworks.sockbowlgame.ratelimit;

import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-M4-V1-05/D26: over real HTTP (the servlet container's actual valve
 * pipeline, not MockMvc, since {@code RemoteIpValve} only runs there) with
 * {@code server.forward-headers-strategy=native}, {@code internal-proxies}
 * trusting only the loopback address the test connects from, and
 * {@code remote-ip-header=CF-Connecting-IP}: the IP-keyed {@code session-join}
 * bucket follows {@code CF-Connecting-IP}, and a spoofed
 * {@code X-Forwarded-For} on the same connection is ignored outright (Tomcat's
 * {@code RemoteIpValve} reads only the header it is configured with).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sockbowl.auth.enabled=false",
        "sockbowl.quota.enabled=false",
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
        "server.tomcat.remoteip.remote-ip-header=CF-Connecting-IP"
})
class CfConnectingIpIT {

    private static final String JOIN_BY_CODE = "/api/v1/session/join-game-session-by-code";

    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
        ShippedLimitProperties.registerRateLimits(registry);
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> joinAsUnknownCode(String cfConnectingIp, String forwardedFor) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + JOIN_BY_CODE))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"joinCode\":\"NOPE99\",\"name\":\"Guesty\"}"));
        if (cfConnectingIp != null) {
            builder.header("CF-Connecting-IP", cfConnectingIp);
        }
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bucketKeyFollowsCfConnectingIpAndIgnoresASpoofedXForwardedFor() throws Exception {
        String realClient = "203.0.113.50";
        String spoofedXff = "203.0.113.99";

        // session-join: 20/min per IP. All 20 land on the same CF-Connecting-IP
        // bucket even though X-Forwarded-For is a different address on every call.
        for (int i = 1; i <= 20; i++) {
            HttpResponse<String> r = joinAsUnknownCode(realClient, spoofedXff + "-" + i);
            assertThat(r.statusCode()).as("join #%d", i).isEqualTo(404);
        }

        HttpResponse<String> limited = joinAsUnknownCode(realClient, spoofedXff);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue(LimitErrorResponses.X_RATE_LIMIT_POLICY))
                .contains("session-join");

        // A different CF-Connecting-IP is a fresh bucket, proving the key is the
        // header value and not e.g. the loopback peer address shared by every call.
        HttpResponse<String> freshRealIp = joinAsUnknownCode("203.0.113.51", spoofedXff);
        assertThat(freshRealIp.statusCode()).isEqualTo(404);

        // Changing only the spoofed X-Forwarded-For does not create a new bucket:
        // the original CF-Connecting-IP is still exhausted, so RemoteIpValve must
        // be ignoring X-Forwarded-For entirely once it is configured to read
        // CF-Connecting-IP instead.
        HttpResponse<String> stillLimited = joinAsUnknownCode(realClient, "198.51.100.7");
        assertThat(stillLimited.statusCode()).isEqualTo(429);
    }
}
