package com.soulsoftworks.sockbowlgame.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * WebSocket transport limits and STOMP throttling notices, bound from
 * {@code sockbowl.websocket.*} (plan m4-limits section 2.5, WP-G3). The defaults
 * equal the shipped {@code application.properties} values, so a context whose
 * properties do not set them (the test classpath shadows the main file) gets
 * the same limits.
 */
@Data
@ConfigurationProperties("sockbowl.websocket")
public class WebSocketLimitsProperties {

    /** Largest inbound STOMP message (also the container's text/binary buffer). */
    private DataSize messageSizeLimit = DataSize.ofKilobytes(16);

    /** Outbound bytes buffered per session before a slow client is disconnected. */
    private DataSize sendBufferSizeLimit = DataSize.ofKilobytes(512);

    /** How long one outbound send may take before a slow client is disconnected. */
    private Duration sendTimeLimit = Duration.ofSeconds(15);

    /** A socket that sends no STOMP frame (CONNECT) within this time is closed. */
    private Duration timeToFirstMessage = Duration.ofSeconds(30);

    /** At most one {@code RATE_LIMITED} notice per connection per this interval. */
    private Duration rateLimitedNoticeInterval = Duration.ofSeconds(1);
}
