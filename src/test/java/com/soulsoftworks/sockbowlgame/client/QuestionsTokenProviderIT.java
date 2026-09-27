package com.soulsoftworks.sockbowlgame.client;

import com.soulsoftworks.sockbowlgame.client.QuestionsUnavailableException.Reason;
import com.soulsoftworks.sockbowlgame.config.JwtDecoderConfig;
import com.soulsoftworks.sockbowlgame.config.ServiceOAuthClientConfig;
import com.soulsoftworks.sockbowlgame.config.SockbowlQuestionsConfig;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service-token path against a real Keycloak (plan m2-auth WP-G4, AUTH-18),
 * using the shared test realm ({@code keycloak/test-realm.json}, the same
 * fixture as the questions auth ITs): the game's real OAuth2 client wiring
 * ({@link ServiceOAuthClientConfig} plus Spring Boot's OAuth2 client
 * auto-configuration, configured with the keys from application.properties).
 *
 * <ul>
 *   <li>the right secret yields a token that passes the game's own decoder
 *       (issuer and {@code aud=sockbowl-api}) and whose realm roles are exactly
 *       the service account's read roles, {@code packet:read-answers} included;</li>
 *   <li>a wrong secret gives {@link QuestionsUnavailableException} with reason
 *       {@code TOKEN} and nothing else escapes;</li>
 *   <li>the context starts while Keycloak is down (no discovery, no token call
 *       at boot), and the first fetch then fails with {@code TOKEN}.</li>
 * </ul>
 */
@Testcontainers
class QuestionsTokenProviderIT {

    private static final String REALM = "sockbowl-test";
    private static final String BACKEND_CLIENT = "sockbowl-game-backend";
    private static final String BACKEND_SECRET = "test-backend-secret";

    /** The permission roles a service token must NOT carry (it is not a user). */
    private static final List<String> USER_PERMISSIONS = List.of(
            "packet:create", "packet:update", "packet:delete", "packet:manage-any", "question:generate",
            "taxonomy:manage", "game:host", "user:ban", "admin:access");

    /**
     * Keycloak 26.7 refuses to import a realm file whose name isn't
     * {@code <realm>-realm.json}, so the shared fixture is copied in under the
     * name it expects instead of via {@code withRealmImportFile} (the container
     * always starts Keycloak with {@code --import-realm}).
     */
    @Container
    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.7.4")
            .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/test-realm.json"),
                    "/opt/keycloak/data/import/" + REALM + "-realm.json");

    @Configuration
    @EnableConfigurationProperties
    @Import({SockbowlQuestionsConfig.class, ServiceOAuthClientConfig.class, JwtDecoderConfig.class,
            QuestionsTokenProvider.class})
    static class ServiceTokenWiring {
    }

    private static ApplicationContextRunner runner(String issuer, String secret) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
                .withUserConfiguration(ServiceTokenWiring.class)
                .withPropertyValues(
                        "sockbowl.auth.enabled=true",
                        "sockbowl.auth.audience=sockbowl-api",
                        "sockbowl.questions.url=http://127.0.0.1:1/",
                        "sockbowl.questions.token-timeout=5s",
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer,
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + issuer + "/protocol/openid-connect/certs",
                        "spring.security.oauth2.client.provider.keycloak.token-uri=" + issuer + "/protocol/openid-connect/token",
                        "spring.security.oauth2.client.registration.questions-svc.provider=keycloak",
                        "spring.security.oauth2.client.registration.questions-svc.client-id=" + BACKEND_CLIENT,
                        "spring.security.oauth2.client.registration.questions-svc.client-secret=" + secret,
                        "spring.security.oauth2.client.registration.questions-svc.authorization-grant-type=client_credentials");
    }

    private static String issuer() {
        return KEYCLOAK.getAuthServerUrl().replaceAll("/+$", "") + "/realms/" + REALM;
    }

    @Test
    @SuppressWarnings("unchecked")
    void rightSecretYieldsAnAudienceBoundTokenWithReadAnswers() {
        runner(issuer(), BACKEND_SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            QuestionsTokenProvider provider = context.getBean(QuestionsTokenProvider.class);

            String token = provider.getTokenOrNull();
            assertThat(token).isNotBlank();

            // The game's own decoder accepts it: signature, issuer, aud=sockbowl-api.
            Jwt jwt = context.getBean(JwtDecoder.class).decode(token);
            assertThat(jwt.getAudience()).contains("sockbowl-api");
            assertThat(jwt.getClaimAsString("azp")).isEqualTo(BACKEND_CLIENT);

            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            List<String> roles = (List<String>) realmAccess.get("roles");
            assertThat(roles).contains("packet:read", "packet:read-answers");
            assertThat(roles).doesNotContainAnyElementsOf(USER_PERMISSIONS);

            // Cached: a second call reuses the unexpired token.
            assertThat(provider.getTokenOrNull()).isEqualTo(token);
            // After invalidation a fresh one is minted, and still works.
            provider.invalidate();
            String fresh = provider.getTokenOrNull();
            assertThat(fresh).isNotBlank();
            assertThat(context.getBean(JwtDecoder.class).decode(fresh).getAudience()).contains("sockbowl-api");
        });
    }

    @Test
    void wrongSecretGivesTokenUnavailable() {
        runner(issuer(), "not-the-secret").run(context -> {
            assertThat(context).hasNotFailed();
            QuestionsTokenProvider provider = context.getBean(QuestionsTokenProvider.class);

            assertThatThrownBy(provider::getTokenOrNull)
                    .isExactlyInstanceOf(QuestionsUnavailableException.class)
                    .satisfies(e -> {
                        assertThat(((QuestionsUnavailableException) e).getReason()).isEqualTo(Reason.TOKEN);
                        assertThat(e.getMessage()).doesNotContain("not-the-secret");
                    });
            // Repeated failures keep the same typed outcome (logged once per burst).
            assertThatThrownBy(provider::getTokenOrNull).isExactlyInstanceOf(QuestionsUnavailableException.class);
        });
    }

    @Test
    void contextStartsWhileKeycloakIsDownAndTheFirstFetchFailsTyped() {
        // Nothing listens on port 1: any discovery or eager token call at boot would fail the context.
        String deadIssuer = "http://127.0.0.1:1/realms/" + REALM;
        runner(deadIssuer, BACKEND_SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OAuth2AuthorizedClientManager.class);
            assertThat(context).hasSingleBean(JwtDecoder.class);

            long start = System.nanoTime();
            assertThatThrownBy(context.getBean(QuestionsTokenProvider.class)::getTokenOrNull)
                    .isExactlyInstanceOf(QuestionsUnavailableException.class)
                    .extracting(e -> ((QuestionsUnavailableException) e).getReason()).isEqualTo(Reason.TOKEN);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        });
    }

    @Test
    void authDisabledNeedsNoTokenAndNoManager() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServiceTokenWiring.class)
                .withPropertyValues("sockbowl.auth.enabled=false", "sockbowl.questions.url=http://127.0.0.1:1/")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OAuth2AuthorizedClientManager.class);
                    assertThat(context.getBean(QuestionsTokenProvider.class).getTokenOrNull()).isNull();
                });
    }
}
