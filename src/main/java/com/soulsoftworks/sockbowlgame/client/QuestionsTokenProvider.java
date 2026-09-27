package com.soulsoftworks.sockbowlgame.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supplies a bearer access token for server-to-server (client_credentials) calls
 * from sockbowl-game to sockbowl-questions.
 *
 * <p>When {@code sockbowl.auth.enabled=false} (guest mode) or no authorized client
 * manager is available, {@link #getTokenOrNull()} returns {@code null} and callers
 * send the request without an Authorization header, preserving guest behavior.
 *
 * <p>With auth on, every failure to obtain a token (Keycloak down or slow, wrong
 * client secret, a disabled client) surfaces as
 * {@link QuestionsUnavailableException} with reason {@code TOKEN} (AUTH-18), never
 * as a raw Spring Security exception. Failures are logged once per burst: the
 * first failure after a success is a WARN, the rest are DEBUG until a token is
 * obtained again. Neither the secret nor the token is ever logged.
 */
@Component
public class QuestionsTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(QuestionsTokenProvider.class);

    /** The client-credentials registration (application.properties). */
    public static final String REG_ID = "questions-svc";

    private final boolean authEnabled;
    private final OAuth2AuthorizedClientManager manager;
    private final OAuth2AuthorizedClientService clientService;
    private final AtomicBoolean failing = new AtomicBoolean(false);

    public QuestionsTokenProvider(
            @Value("${sockbowl.auth.enabled:false}") boolean authEnabled,
            ObjectProvider<OAuth2AuthorizedClientManager> managerProvider,
            ObjectProvider<OAuth2AuthorizedClientService> clientServiceProvider) {
        this.authEnabled = authEnabled;
        this.manager = managerProvider.getIfAvailable();
        this.clientService = clientServiceProvider.getIfAvailable();
    }

    /**
     * Returns a bearer access token for the {@code questions-svc} client-credentials
     * registration, or {@code null} when auth is disabled or no client manager is
     * configured. A cached token is reused until it expires.
     *
     * @throws QuestionsUnavailableException ({@code TOKEN}) when auth is on and no
     *                                       token could be obtained
     */
    public String getTokenOrNull() {
        if (!authEnabled || manager == null) {
            return null;
        }

        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(REG_ID)
                .principal(REG_ID) // client_credentials has no end-user; a stable name is fine
                .build();

        OAuth2AuthorizedClient client;
        try {
            client = manager.authorize(request);
        } catch (OAuth2AuthorizationException e) {
            throw failure(describe(e.getError()), e);
        } catch (RuntimeException e) {
            // Anything else from the token call (a misconfigured registration, an
            // unexpected client error) is still "no token", not a crash.
            throw failure(e.getClass().getSimpleName(), e);
        }
        if (client == null || client.getAccessToken() == null) {
            throw failure("no authorized client for " + REG_ID, null);
        }
        if (failing.compareAndSet(true, false)) {
            log.info("Service token for sockbowl-questions obtained again; recovered");
        }
        return client.getAccessToken().getTokenValue();
    }

    /**
     * Forget the cached service token, so the next {@link #getTokenOrNull()}
     * fetches a fresh one. Called when questions rejects the token (for example
     * after the service account's roles or the realm keys changed).
     */
    public void invalidate() {
        if (clientService != null) {
            clientService.removeAuthorizedClient(REG_ID, REG_ID);
        }
    }

    private QuestionsUnavailableException failure(String detail, Throwable cause) {
        String message = "could not obtain a service token for sockbowl-questions (" + detail + ")";
        if (failing.compareAndSet(false, true)) {
            log.warn("{}; further failures are logged at DEBUG until a token is obtained", message);
        } else {
            log.debug(message);
        }
        // The cause is kept for debugging in-process, but the message is what
        // callers log, and it carries only the OAuth2 error code/description.
        return new QuestionsUnavailableException(QuestionsUnavailableException.Reason.TOKEN, detail, cause);
    }

    private static String describe(OAuth2Error error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getDescription() == null
                ? error.getErrorCode()
                : error.getErrorCode() + ": " + error.getDescription();
    }
}
