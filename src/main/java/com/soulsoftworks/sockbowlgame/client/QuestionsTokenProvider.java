package com.soulsoftworks.sockbowlgame.client;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

/**
 * Supplies a bearer access token for server-to-server (client_credentials) calls
 * from sockbowl-game to sockbowl-questions.
 *
 * When {@code sockbowl.auth.enabled=false} (guest mode) or no authorized client
 * manager is available, {@link #getTokenOrNull()} returns {@code null} and callers
 * must send the request without an Authorization header, preserving guest behavior.
 */
@Component
public class QuestionsTokenProvider {

    private static final String REG_ID = "questions-svc";

    private final boolean authEnabled;
    private final OAuth2AuthorizedClientManager manager;

    public QuestionsTokenProvider(
            @Value("${sockbowl.auth.enabled:false}") boolean authEnabled,
            ObjectProvider<OAuth2AuthorizedClientManager> managerProvider) {
        this.authEnabled = authEnabled;
        this.manager = managerProvider.getIfAvailable();
    }

    /**
     * Returns a bearer access token for the {@code questions-svc} client-credentials
     * registration, or {@code null} when auth is disabled or unavailable.
     */
    public String getTokenOrNull() {
        if (!authEnabled || manager == null) {
            return null;
        }

        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(REG_ID)
                .principal(REG_ID) // client_credentials has no end-user; a stable name is fine
                .build();

        OAuth2AuthorizedClient client = manager.authorize(request);
        return client != null ? client.getAccessToken().getTokenValue() : null;
    }
}
