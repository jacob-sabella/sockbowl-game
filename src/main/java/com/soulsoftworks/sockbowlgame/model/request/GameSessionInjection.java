package com.soulsoftworks.sockbowlgame.model.request;

import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Resolved, validated context injected into WebSocket message handlers. Carries
 * the player identifiers, the live game session, and the security identity
 * established at the edge ({@link AuthenticatedUser#guest()} for guest players).
 */
@Data
@AllArgsConstructor
public class GameSessionInjection {
    private PlayerIdentifiers playerIdentifiers;
    private String gameSessionId;
    private GameSession gameSession;
    private AuthenticatedUser identity;
}
