package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates a STOMP CONNECT and builds the connection's
 * {@link StompPrincipal} (plan m2-auth section 2.5, AUTH-02).
 *
 * <p>Native CONNECT headers: {@code gameSessionId}, {@code playerSessionId}, and
 * either {@code Authorization: Bearer <jwt>} or {@code playerSecret}.
 * <ol>
 *   <li>Missing ids: {@code AUTH_REQUIRED}. Unknown session:
 *       {@code SESSION_NOT_FOUND}. Player not in it: {@code PLAYER_NOT_IN_SESSION}.</li>
 *   <li><b>Auth on, non-guest player</b> (it joined with a JWT): a bearer token
 *       is required ({@code AUTH_REQUIRED}); it is decoded with the shared
 *       audience-bound decoder ({@code INVALID_CREDENTIALS}, or
 *       {@code TOKEN_EXPIRED}); a service-account token is refused
 *       ({@code INVALID_CREDENTIALS}); its {@code sub} must be the player's
 *       Keycloak id ({@code IDENTITY_MISMATCH}); and the user must not be banned
 *       ({@code BANNED}).</li>
 *   <li><b>Guest player, or auth off</b>: the {@code playerSecret} header must
 *       match, compared in constant time ({@code AUTH_REQUIRED} when absent,
 *       {@code INVALID_CREDENTIALS} when wrong). Any JWT is ignored.</li>
 * </ol>
 */
@Component
public class StompConnectAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(StompConnectAuthenticator.class);

    static final String GAME_SESSION_ID = "gameSessionId";
    static final String PLAYER_SESSION_ID = "playerSessionId";
    static final String PLAYER_SECRET = "playerSecret";

    private final SessionService sessionService;
    private final GameAuthorizationPolicy authorizationPolicy;
    private final ObjectProvider<JwtDecoder> jwtDecoder;

    public StompConnectAuthenticator(SessionService sessionService,
                                     GameAuthorizationPolicy authorizationPolicy,
                                     ObjectProvider<JwtDecoder> jwtDecoder) {
        this.sessionService = sessionService;
        this.authorizationPolicy = authorizationPolicy;
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * @return the principal to bind to the connection
     * @throws StompRejectedException on any failure (fatal: ERROR frame, socket closed)
     */
    public StompPrincipal authenticate(StompHeaderAccessor accessor) {
        String gameSessionId = accessor.getFirstNativeHeader(GAME_SESSION_ID);
        String playerSessionId = accessor.getFirstNativeHeader(PLAYER_SESSION_ID);
        if (isBlank(gameSessionId) || isBlank(playerSessionId)) {
            throw new StompRejectedException(StompErrorCode.AUTH_REQUIRED,
                    "CONNECT requires gameSessionId and playerSessionId headers");
        }

        GameSession session = sessionService.getGameSessionById(gameSessionId);
        if (session == null) {
            throw new StompRejectedException(StompErrorCode.SESSION_NOT_FOUND, "Game session not found");
        }
        Player player = session.getPlayerById(playerSessionId);
        if (player == null) {
            throw new StompRejectedException(StompErrorCode.PLAYER_NOT_IN_SESSION,
                    "Player is not part of the game session");
        }

        boolean jwtPlayer = !player.isGuest() || player.getKeycloakId() != null;
        if (authorizationPolicy.isAuthEnabled() && jwtPlayer) {
            return authenticateUser(accessor, gameSessionId, playerSessionId, player);
        }
        return authenticateGuest(accessor, gameSessionId, playerSessionId, player);
    }

    private StompPrincipal authenticateUser(StompHeaderAccessor accessor, String gameSessionId,
                                            String playerSessionId, Player player) {
        String token = StompTokens.bearerToken(StompTokens.authorizationHeader(accessor));
        if (token == null) {
            throw new StompRejectedException(StompErrorCode.AUTH_REQUIRED,
                    "This player joined signed in; a bearer token is required");
        }
        Jwt jwt = StompTokens.decode(jwtDecoder.getIfAvailable(), token);
        AuthenticatedUser identity = authorizationPolicy.identityOf(jwt);
        if (identity.isService()) {
            throw new StompRejectedException(StompErrorCode.INVALID_CREDENTIALS,
                    "Service tokens cannot connect as a player");
        }
        if (player.getKeycloakId() == null || !player.getKeycloakId().equals(jwt.getSubject())) {
            log.warn("STOMP CONNECT identity mismatch for player {} in game {}", playerSessionId, gameSessionId);
            throw new StompRejectedException(StompErrorCode.IDENTITY_MISMATCH,
                    "Token does not belong to this player");
        }
        if (authorizationPolicy.isBanned(identity)) {
            throw new StompRejectedException(StompErrorCode.BANNED,
                    "Your account is banned and cannot participate in games.");
        }
        return StompPrincipal.user(gameSessionId, playerSessionId, jwt.getSubject(),
                identity.getAuthorities(), jwt.getExpiresAt());
    }

    private StompPrincipal authenticateGuest(StompHeaderAccessor accessor, String gameSessionId,
                                             String playerSessionId, Player player) {
        String secret = accessor.getFirstNativeHeader(PLAYER_SECRET);
        if (secret == null || secret.isEmpty()) {
            throw new StompRejectedException(StompErrorCode.AUTH_REQUIRED, "CONNECT requires a playerSecret header");
        }
        if (!constantTimeEquals(player.getPlayerSecret(), secret)) {
            throw new StompRejectedException(StompErrorCode.INVALID_CREDENTIALS, "Invalid player credentials");
        }
        return StompPrincipal.guest(gameSessionId, playerSessionId);
    }

    /** Constant-time comparison; a null expected secret never matches. */
    static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
