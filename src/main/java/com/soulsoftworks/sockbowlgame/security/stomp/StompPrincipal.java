package com.soulsoftworks.sockbowlgame.security.stomp;

import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * The identity bound to a STOMP connection at CONNECT (via
 * {@code StompHeaderAccessor#setUser}); every later frame on the socket carries
 * it. Every connection, guests included, gets one, so
 * {@code /user/queue/errors} and {@code convertAndSendToUser(principal.getName(), ...)}
 * work for everybody.
 *
 * <p>{@link #getName()} is {@code gameSessionId + ":" + playerSessionId}, so it
 * is unique per player seat.
 *
 * <p>The token expiry (and the authorities, which may change on refresh) are
 * updated when the client sends a fresh {@code Authorization} header on a SEND
 * (see {@link StompDestinationGuard}).
 */
public final class StompPrincipal implements Principal {

    private final String gameSessionId;
    private final String playerSessionId;
    private final String keycloakId;
    private final boolean guest;
    private volatile Set<String> authorities;
    private volatile Instant tokenExpiresAt;

    public StompPrincipal(String gameSessionId, String playerSessionId, String keycloakId,
                          Set<String> authorities, Instant tokenExpiresAt, boolean guest) {
        this.gameSessionId = Objects.requireNonNull(gameSessionId, "gameSessionId");
        this.playerSessionId = Objects.requireNonNull(playerSessionId, "playerSessionId");
        this.keycloakId = keycloakId;
        this.authorities = authorities == null ? Collections.emptySet() : Set.copyOf(authorities);
        this.tokenExpiresAt = tokenExpiresAt;
        this.guest = guest;
    }

    /** A guest (player-secret) connection: no subject, no authorities, no expiry. */
    public static StompPrincipal guest(String gameSessionId, String playerSessionId) {
        return new StompPrincipal(gameSessionId, playerSessionId, null, Collections.emptySet(), null, true);
    }

    /** An authenticated (JWT) connection. */
    public static StompPrincipal user(String gameSessionId, String playerSessionId, String keycloakId,
                                      Set<String> authorities, Instant tokenExpiresAt) {
        return new StompPrincipal(gameSessionId, playerSessionId, keycloakId, authorities, tokenExpiresAt, false);
    }

    @Override
    public String getName() {
        return gameSessionId + ":" + playerSessionId;
    }

    public String getGameSessionId() {
        return gameSessionId;
    }

    public String getPlayerSessionId() {
        return playerSessionId;
    }

    /** The Keycloak subject; {@code null} for guests. */
    public String getKeycloakId() {
        return keycloakId;
    }

    /** Raw authority names (realm/permission roles); empty for guests. Immutable. */
    public Set<String> getAuthorities() {
        return authorities;
    }

    /** Expiry of the most recent token seen on this connection; {@code null} for guests. */
    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public boolean isGuest() {
        return guest;
    }

    /** Record a refreshed token's expiry and authorities. */
    void refresh(Instant newExpiresAt, Set<String> newAuthorities) {
        this.tokenExpiresAt = newExpiresAt;
        if (newAuthorities != null) {
            this.authorities = Set.copyOf(newAuthorities);
        }
    }

    @Override
    public String toString() {
        // Never include credentials; the subject is fine for logs.
        return "StompPrincipal[" + getName() + (guest ? ", guest" : ", sub=" + keycloakId) + "]";
    }
}
