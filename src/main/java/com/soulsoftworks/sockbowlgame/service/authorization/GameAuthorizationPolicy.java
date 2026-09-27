package com.soulsoftworks.sockbowlgame.service.authorization;

import com.soulsoftworks.sockbowlgame.controller.exception.UserBannedException;
import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Set;

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

    /** Fine-grained permission authority names (Keycloak realm role names). */
    private static final String GAME_HOST = "game:host";
    private static final String USER_BAN = "user:ban";
    /** May read and manage any packet, including other users' drafts. */
    public static final String PACKET_MANAGE_ANY = "packet:manage-any";

    /**
     * Visibilities any player may load into a match (D2, D15): PUBLISHED, and
     * EPHEMERAL (game-only packets generated for guests and players). Matched
     * by name so this build doesn't depend on a models jar that knows every
     * value; anything else (DRAFT, or a value added later) needs the owner or
     * {@code packet:manage-any}.
     */
    private static final Set<String> OPEN_TO_ANY_SETTER = Set.of("PUBLISHED", "EPHEMERAL");

    private final boolean authEnabled;

    /** {@code sockbowl.auth.service-client-id}: the backend service account's client id. */
    private final String serviceClientId;

    /**
     * Optional - only present when {@code sockbowl.auth.enabled=true}. When
     * absent the policy treats every user as un-banned.
     */
    private final BanService banService;

    @Autowired
    public GameAuthorizationPolicy(
            @Value("${sockbowl.auth.enabled:false}") boolean authEnabled,
            @Autowired(required = false) BanService banService,
            @Value("${sockbowl.auth.service-client-id:" + AuthenticatedUser.DEFAULT_SERVICE_CLIENT_ID + "}")
            String serviceClientId) {
        this.authEnabled = authEnabled;
        this.banService = banService;
        this.serviceClientId = serviceClientId;
    }

    /** Convenience for tests: the default service client id. */
    public GameAuthorizationPolicy(boolean authEnabled, BanService banService) {
        this(authEnabled, banService, AuthenticatedUser.DEFAULT_SERVICE_CLIENT_ID);
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    /* ------------------------------------------------------------------ */
    /* Identity resolution                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Resolve the identity behind a validated token, using the configured
     * service client id to recognise backend service accounts. A null token is
     * the guest identity.
     */
    public AuthenticatedUser identityOf(Jwt jwt) {
        return AuthenticatedUser.fromJwt(jwt, serviceClientId);
    }

    /**
     * True when the identity is a backend service account rather than a user.
     * Service identities may not host, join or use user endpoints.
     */
    public boolean isServiceIdentity(AuthenticatedUser identity) {
        return identity != null && identity.isService();
    }

    /** True when the token was issued to the backend service client. */
    public boolean isServiceToken(Jwt jwt) {
        return AuthenticatedUser.isServiceToken(jwt, serviceClientId);
    }

    /* ------------------------------------------------------------------ */
    /* Capability checks (identity based)                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Whether an identity may create a new game session (decision D1, AUTH-19).
     *
     * <ul>
     *   <li>Auth disabled: anyone.</li>
     *   <li>Guest (no token): allowed. Auth is additive, so enabling it never
     *       takes hosting away from guests; M4 adds per-IP limits.</li>
     *   <li>Service identity: never (it is not a user).</li>
     *   <li>Signed-in user: must hold {@code game:host} and not be banned.
     *       Every tier includes {@code game:host} via the {@code player}
     *       composite, so in practice only a revoked permission or a ban
     *       blocks it.</li>
     * </ul>
     */
    public boolean canCreateGame(AuthenticatedUser identity) {
        if (!authEnabled) {
            return true;
        }
        if (identity == null || identity.isGuest()) {
            return true;
        }
        if (identity.isService() || !identity.isAuthenticated()) {
            return false;
        }
        return identity.hasAuthority(GAME_HOST) && !isBanned(identity);
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
        return identity != null && identity.hasAuthority(USER_BAN);
    }

    /**
     * True when the identity is an authenticated user with an active ban.
     * {@link BanService#isBanned} answers from its {@code BanStatusCache}
     * (Redis mirror, 30s local cache, Postgres fallback), so the per-message
     * STOMP check costs no Postgres query (M4 RL-06).
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
     * Whether the given player is the owner of the session. This is the only
     * ownership check in the game server (AUTH-11): processors, the timer
     * service and the other session checks below all call it instead of
     * reading {@link Player#isGameOwner()} directly.
     *
     * <p>The owner flag is set once, in {@link GameSession#addPlayer}. When the
     * session was created by an authenticated user ({@code gameOwnerId} set),
     * the player's stored Keycloak subject is re-checked against it as well, so
     * a flag that disagrees with the durable owner identity never grants
     * ownership. Guest-created sessions use the first-joiner flag alone.
     * Independent of {@code sockbowl.auth.enabled}.
     */
    public boolean isSessionOwner(GameSession session, String playerId) {
        if (session == null || playerId == null) {
            return false;
        }
        String ownerKeycloakId = session.getGameOwnerId();
        if (ownerKeycloakId != null && !ownerKeycloakId.isBlank()) {
            Player player = session.getPlayerById(playerId);
            return player != null
                    && player.isGameOwner()
                    && ownerKeycloakId.equals(player.getKeycloakId());
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

    /* ------------------------------------------------------------------ */
    /* Packet checks                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Whether the sender of a {@code SetMatchPacket} may load this packet into
     * the match (AUTH-03, D2, D15). This is the only visibility check in the
     * game server; the game fetches packets with its service token, which can
     * read every packet in full, so without it anyone could load someone
     * else's draft and read its answers as proctor.
     *
     * <ul>
     *   <li>Auth disabled: always (matches questions' PacketReadPolicy, so
     *       play-testing a draft works in auth-off local dev).</li>
     *   <li>PUBLISHED, EPHEMERAL, or no visibility (a legacy packet, read as
     *       PUBLISHED): anyone, guests included.</li>
     *   <li>Otherwise (DRAFT): only the packet's owner (the sender's Keycloak
     *       subject equals {@code ownerId}) or a holder of
     *       {@code packet:manage-any}.</li>
     * </ul>
     *
     * @param packet            the packet as fetched from sockbowl-questions
     * @param senderKeycloakId  {@code SockbowlInMessage.originatingKeycloakId}
     *                          (null for guests)
     * @param senderAuthorities {@code SockbowlInMessage.originatingAuthorities}
     */
    public boolean canUsePacketForMatch(Packet packet, String senderKeycloakId, Set<String> senderAuthorities) {
        if (!authEnabled) {
            return true;
        }
        if (packet == null) {
            return false;
        }
        PacketVisibility visibility = packet.getVisibility();
        if (visibility == null || OPEN_TO_ANY_SETTER.contains(visibility.name())) {
            return true;
        }
        if (senderAuthorities != null && senderAuthorities.contains(PACKET_MANAGE_ANY)) {
            return true;
        }
        return senderKeycloakId != null
                && !senderKeycloakId.isBlank()
                && senderKeycloakId.equals(packet.getOwnerId());
    }
}
