package com.soulsoftworks.sockbowlgame.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Provides an {@link OAuth2AuthorizedClientManager} suitable for service-to-service
 * (client_credentials) token acquisition, where there is no HTTP request context or
 * end-user principal (e.g. fetching packets from sockbowl-questions from deep inside
 * an async WebSocket message flow).
 *
 * Only active when {@code sockbowl.auth.enabled=true}. When auth is disabled, no
 * bean is created and {@link com.soulsoftworks.sockbowlgame.client.QuestionsTokenProvider}
 * falls back to returning no token, preserving guest (unauthenticated) behavior.
 */
@Configuration
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class ServiceOAuthClientConfig {

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }
}
