package com.soulsoftworks.sockbowlgame.model.request;

import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.security.stomp.StompPrincipal;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.Set;

/**
 * Resolved, validated context injected into WebSocket message handlers. Built
 * from the connection's {@link StompPrincipal} (bound at STOMP CONNECT), never
 * from SEND headers or the message body. Carries the player identifiers, the
 * live game session, the security identity ({@link AuthenticatedUser#guest()}
 * for guest players) and the principal itself.
 */
@Data
@AllArgsConstructor
public class GameSessionInjection {
    private PlayerIdentifiers playerIdentifiers;
    private String gameSessionId;
    private GameSession gameSession;
    private AuthenticatedUser identity;
    private StompPrincipal principal;

    /** The Keycloak subject of the sender; {@code null} for guests. */
    public String getKeycloakId() {
        return principal == null || principal.isGuest() ? null : principal.getKeycloakId();
    }

    /** The sender's authority names; empty for guests. */
    public Set<String> getAuthorities() {
        return principal == null || principal.isGuest() ? Collections.emptySet() : principal.getAuthorities();
    }
}
