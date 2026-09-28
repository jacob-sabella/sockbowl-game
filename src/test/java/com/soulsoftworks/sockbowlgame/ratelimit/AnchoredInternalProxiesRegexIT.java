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
 * D26/H4: {@code server.tomcat.remoteip.internal-proxies} must be matched as
 * a <b>full, anchored</b> regex (what Tomcat's {@code RemoteIpValve} actually
 * does with {@link java.util.regex.Matcher#matches()}), not a substring
 * search. Here the configured regex ({@code "1"}) is a substring of the test
 * client's loopback peer address ({@code 127.0.0.1}) but does not fully match
 * it, so the peer must be treated as <b>untrusted</b>: {@code
 * CF-Connecting-IP} is never honored, and every request keys on the same raw
 * peer address regardless of what the header claims. If matching were ever
 * loosened to a substring search, this test would instead see the
 * {@code CF-Connecting-IP} values sorted into separate buckets, exactly like
 * {@link CfConnectingIpIT}, and fail.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sockbowl.auth.enabled=false",
        "sockbowl.quota.enabled=false",
        "server.forward-headers-strategy=native",
        // A bare "1" is a substring of 127.0.0.1 but not the whole address:
        // Pattern.matches("1", "127.0.0.1") is false, Pattern.find() would be true.
        "server.tomcat.remoteip.internal-proxies=1",
        "server.tomcat.remoteip.remote-ip-header=CF-Connecting-IP"
})
class AnchoredInternalProxiesRegexIT {

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

    private HttpResponse<String> joinAsUnknownCode(String cfConnectingIp) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + JOIN_BY_CODE))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"joinCode\":\"NOPE99\",\"name\":\"Guesty\"}"));
        if (cfConnectingIp != null) {
            builder.header("CF-Connecting-IP", cfConnectingIp);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void unmatchedInternalProxiesRegexNeverHonorsCfConnectingIp() throws Exception {
        // session-join: 20/min per IP. The peer is untrusted (see class docs),
        // so every one of these - despite carrying a different CF-Connecting-IP
        // each time - is keyed by the same real (loopback) peer address.
        for (int i = 1; i <= 20; i++) {
            HttpResponse<String> r = joinAsUnknownCode("203.0.113." + i);
            assertThat(r.statusCode()).as("join #%d", i).isEqualTo(404);
        }

        HttpResponse<String> limited = joinAsUnknownCode("203.0.113.50");
        assertThat(limited.statusCode()).isEqualTo(429);

        // A "fresh" CF-Connecting-IP does not free up a new bucket: the header
        // is not honored at all, because the loopback peer never fully matches
        // the internal-proxies regex "1".
        HttpResponse<String> stillLimited = joinAsUnknownCode("198.51.100.7");
        assertThat(stillLimited.statusCode()).isEqualTo(429);
    }
}
