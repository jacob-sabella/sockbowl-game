package com.soulsoftworks.sockbowlgame.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Provides an {@link OAuth2AuthorizedClientManager} suitable for service-to-service
 * (client_credentials) token acquisition, where there is no HTTP request context or
 * end-user principal (e.g. fetching packets from sockbowl-questions from deep inside
 * an async WebSocket message flow).
 *
 * <p>Only active when {@code sockbowl.auth.enabled=true}. When auth is disabled, no
 * bean is created and {@link com.soulsoftworks.sockbowlgame.client.QuestionsTokenProvider}
 * falls back to returning no token, preserving guest (unauthenticated) behavior.
 *
 * <p>Nothing here talks to Keycloak at startup: the registration names an explicit
 * {@code token-uri} (no issuer discovery), and the first token is fetched lazily on
 * the first packet fetch, so the game boots while Keycloak is still down (AUTH-18).
 * The token call gets connect/read timeouts ({@code sockbowl.questions.token-timeout})
 * so a stalled Keycloak can't hang a Kafka listener thread.
 */
@Configuration
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class ServiceOAuthClientConfig {

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            SockbowlQuestionsConfig questionsConfig) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(cc -> cc.accessTokenResponseClient(tokenResponseClient(questionsConfig)))
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /**
     * Spring Security's default client-credentials token client, with the same
     * message converters and OAuth2 error handling, plus timeouts.
     */
    static RestClientClientCredentialsTokenResponseClient tokenResponseClient(SockbowlQuestionsConfig config) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(config.getTokenTimeout());
        requestFactory.setReadTimeout(config.getTokenTimeout());

        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();

        RestClientClientCredentialsTokenResponseClient client = new RestClientClientCredentialsTokenResponseClient();
        client.setRestClient(restClient);
        return client;
    }
}
