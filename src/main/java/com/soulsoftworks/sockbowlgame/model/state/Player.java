package com.soulsoftworks.sockbowlgame.model.state;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Player {
    private PlayerMode playerMode;
    private boolean isGameOwner;
    @Builder.Default
    private PlayerStatus playerStatus = PlayerStatus.DISCONNECTED;
    private String playerId;
    private String playerSecret;
    private String name;

    // Authentication fields
    /**
     * UUID of the authenticated User entity (null for guest players).
     * Links this Player to a persistent user account.
     */
    private String userId;

    /**
     * Keycloak subject (sub claim) of the authenticated user backing this player
     * (null for guest players). Used by the authorization policy to verify
     * session ownership against the JWT-validated identity, preventing
     * cross-user impersonation within a session.
     */
    private String keycloakId;

    /**
     * Indicates whether this player is a guest (true) or authenticated user (false).
     * Guest players use header-based authentication with playerSecret.
     * Authenticated players use JWT tokens from Keycloak.
     */
    @Builder.Default
    private boolean isGuest = true;
}
