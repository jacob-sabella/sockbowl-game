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
     * Indicates whether this player is a guest (true) or authenticated user (false).
     * Guest players use header-based authentication with playerSecret.
     * Authenticated players use JWT tokens from Keycloak.
     */
    @Builder.Default
    private boolean isGuest = true;
}
