package com.soulsoftworks.sockbowlgame.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.4 (G1): {@code sockbowl.websocket.allowed-origins} (env {@code
 * SOCKBOWL_GAME_ALLOWED_ORIGINS}, H2) feeds both {@link WebConfig}'s REST CORS
 * mapping and {@link WebSocketConfig}'s STOMP endpoint through the identical
 * {@code allowedOriginPatterns}/{@code setAllowedOriginPatterns} mechanism -
 * Spring's pattern-based {@link CorsConfiguration#checkOrigin}. Exercising it
 * once through the REST mapping proves the prod https origin is accepted by
 * both, and that nothing else is.
 */
class WebConfigAllowedOriginsTest {

    private static CorsConfiguration corsConfigFor(String... origins) {
        WebConfig config = new WebConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", origins);

        // CorsRegistry#getCorsConfigurations() is protected; an anonymous
        // subclass in the caller can still invoke its own inherited method.
        CorsRegistry registry = new CorsRegistry() {
        };
        config.addCorsMappings(registry);
        Map<String, CorsConfiguration> configured = (Map<String, CorsConfiguration>)
                ReflectionTestUtils.invokeMethod(registry, "getCorsConfigurations");
        return configured.get("/**");
    }

    @Test
    void devDefaultAcceptsLocalhostAndRejectsEverythingElse() {
        CorsConfiguration cors = corsConfigFor("http://localhost:4200");
        assertThat(cors.checkOrigin("http://localhost:4200")).isEqualTo("http://localhost:4200");
        assertThat(cors.checkOrigin("http://evil.example")).isNull();
    }

    @Test
    void prodHttpsOriginIsAccepted() {
        // D26/H2: prod sets SOCKBOWL_GAME_ALLOWED_ORIGINS=https://sockbowl.jacobsabella.com.
        CorsConfiguration cors = corsConfigFor("https://sockbowl.jacobsabella.com");
        assertThat(cors.checkOrigin("https://sockbowl.jacobsabella.com"))
                .isEqualTo("https://sockbowl.jacobsabella.com");
        // Neither the plain-http nor an unrelated origin is let in.
        assertThat(cors.checkOrigin("http://sockbowl.jacobsabella.com")).isNull();
        assertThat(cors.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void multipleConfiguredOriginsAreAllAccepted() {
        CorsConfiguration cors = corsConfigFor("http://localhost:4200", "https://sockbowl.jacobsabella.com");
        assertThat(cors.checkOrigin("http://localhost:4200")).isNotNull();
        assertThat(cors.checkOrigin("https://sockbowl.jacobsabella.com")).isNotNull();
    }
}
