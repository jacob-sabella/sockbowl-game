package com.soulsoftworks.sockbowlgame.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "sockbowl.questions")
@Data
public class SockbowlQuestionsConfig {
    private String url;

    /**
     * {@code sockbowl.questions.timeout}: the longest a packet fetch from
     * sockbowl-questions may take before it fails with {@code TIMEOUT}. The
     * fetch runs on a Kafka listener thread, so it must never hang.
     */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * {@code sockbowl.questions.token-timeout}: connect and read timeout for
     * the client-credentials call to Keycloak's token endpoint.
     */
    private Duration tokenTimeout = Duration.ofSeconds(5);
}
