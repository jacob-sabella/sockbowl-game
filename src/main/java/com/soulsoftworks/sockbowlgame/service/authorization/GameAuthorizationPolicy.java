package com.soulsoftworks.sockbowlgame.service.authorization;

import com.soulsoftworks.sockbowlgame.controller.exception.UserBannedException;
import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.service.BanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Central authorization policy for Sockbowl. This is the single place where
 * "who is allowed to do what" is decided. Controllers, the WebSocket argument
 * resolver, and the configuration message processor all delegate here instead of
 * scattering boolean ownership/role checks across the codebase.
 *
 * <p>The policy distinguishes two kinds of question:
 * <ul>
 *   <li><b>Capability</b> checks against a {@link AuthenticatedUser} identity
 *       (create a game, manage bans, admin status, ban enforcement).</li>
 *   <li><b>Session</b> checks against the in-flight {@link GameSession} state
 *       (own / configure a session, change a team, manage the proctor).</li>
 * </ul>
 *
 * <p>Session ownership is resolved against the Keycloak subject stored on the
 * session ({@link GameSession#getGameOwnerId()}) when authentication is in play,
 * which prevents one signed-in user from impersonating another within a session.
 * It falls back to the in-memory first-join-wins {@code isGameOwner} flag for
 * guest/anonymous play.
 */
@Service
public class GameAuthorizationPolicy {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ADMIN = "admin";

    private final boolean authEnabled;

    /**
     * Optional - only present when {@code sockbowl.auth.enabled=true}. When
     * absent the policy treats every user as un-banned.
     */
    private final BanService banService;

    public GameAuthorizationPolicy(
            @Value("${sockbowl.auth.enabled:false}") boolean authEnabled,
            @Autowired(required = false) BanService banService) {
        this.authEnabled = authEnabled;
        this.banService = banService;
    }

    /* ------------------------------------------------------------------ */
    /* Capability checks (identity based)                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Whether an identity may create a new game session.
     * <ul>
     *   <li>Auth disabled: guest mode is fully preserved - anyone may create.</li>
     *   <li>Auth enabled: requires an authenticated, non-banned user holding the
     *       {@code user} (or {@code admin}) capability role.</li>
     * </ul>
     */
    public boolean canCreateGame(AuthenticatedUser identity) {
        if (!authEnabled) {
            return true;
        }
        if (identity == null || !identity.isAuthenticated()) {
            return false;
        }
        if (isBanned(identity)) {
            return false;
        }
        return identity.hasRole(ROLE_USER) || identity.isAdmin();
    }

    /**
     * Whether an identity holds administrative capability (ban management and
     * other admin operations).
     */
    public boolean isAdmin(AuthenticatedUser identity) {
        return identity != null && identity.isAdmin();
    }

    /**
     * Whether an identity may manage bans (view/add/remove).
     */
    public boolean canManageBans(AuthenticatedUser identity) {
        return isAdmin(identity);
    }

    /**
     * True when the identity is an authenticated user with an active ban.
     */
    public boolean isBanned(AuthenticatedUser identity) {
        return banService != null
                && identity != null
                && identity.isAuthenticated()
                && banService.isBanned(identity.getKeycloakId());
    }

    /**
     * Guard that throws {@link UserBannedException} when a banned identity tries
     * to act. No-op for guests, un-banned users, or when bans are not enabled.
     */
    public void ensureNotBanned(AuthenticatedUser identity) {
        if (isBanned(identity)) {
            throw new UserBannedException(
                    "Your account is banned and cannot participate in games.");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Session checks (game-state based)                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Whether the given player is the owner of the session. When the session
     * carries a Keycloak owner subject, ownership is verified against the
     * player's stored Keycloak subject so a different authenticated user cannot
     * assume ownership. Otherwise the in-memory owner flag is used (guest play).
     */
    public boolean isSessionOwner(GameSession session, String playerId) {
        if (session == null || playerId == null) {
            return false;
        }
        String ownerKeycloakId = session.getGameOwnerId();
        if (ownerKeycloakId != null && !ownerKeycloakId.isBlank()) {
            Player player = session.getPlayerById(playerId);
            return player != null && ownerKeycloakId.equals(player.getKeycloakId());
        }
        return session.isPlayerGameOwner(playerId);
    }

    /**
     * Whether the given player may perform owner-level configuration on the
     * session.
     */
    public boolean canConfigureSession(GameSession session, String playerId) {
        return isSessionOwner(session, playerId);
    }

    /**
     * A player may change a team assignment for themselves, or for anyone if they
     * own the session.
     */
    public boolean canChangeTeam(GameSession session, String askingPlayerId, String targetPlayerId) {
        if (askingPlayerId != null && askingPlayerId.equals(targetPlayerId)) {
            return true;
        }
        return isSessionOwner(session, askingPlayerId);
    }

    /**
     * The session owner may assign any proctor. A player may also claim the
     * proctor role for themselves while no proctor is set yet.
     */
    public boolean canManageProctor(GameSession session, String askingPlayerId, String targetPlayerId) {
        if (isSessionOwner(session, askingPlayerId)) {
            return true;
        }
        return askingPlayerId != null
                && askingPlayerId.equals(targetPlayerId)
                && session.getProctor() == null;
    }
}
