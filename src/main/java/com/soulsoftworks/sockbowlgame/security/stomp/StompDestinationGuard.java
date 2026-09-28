package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Destination and identity rules for every post-CONNECT frame (plan m2-auth
 * section 2.5; AUTH-01, AUTH-02, AUTH-12). Order {@value #ORDER}. Applies in
 * both auth modes.
 *
 * <ul>
 *   <li>A SEND, SUBSCRIBE (or any frame other than CONNECT, DISCONNECT,
 *       UNSUBSCRIBE and heart-beats) with no principal: {@code AUTH_REQUIRED}.</li>
 *   <li>Every destination is canonicalized first ({@link #isNonCanonical}):
 *       {@code //}, {@code /./}, a trailing {@code /.} or {@code /}, a
 *       backslash, a percent-encoded byte or {@code ..} anywhere is
 *       {@code FORBIDDEN_DESTINATION}. Spring's {@code AntPathMatcher}
 *       (used by {@code @MessageMapping} routing) treats empty path segments
 *       as no-ops, so without this a SEND to
 *       {@code /app/game//player-incoming-buzz} still reached the buzz
 *       handler while matching none of {@link StompRateLimitGuard}'s exact-string
 *       policy checks (G-M4-V1-02).</li>
 *   <li><b>SEND</b> only to {@code /app/**}, never straight to a broker
 *       destination ({@code FORBIDDEN_DESTINATION}); {@code gameSessionId} /
 *       {@code playerSessionId} headers, when present, must be the principal's
 *       ({@code IDENTITY_MISMATCH}); an {@code Authorization} header on a signed-in
 *       player's SEND is a token refresh (decoded, same {@code sub}, expiry and
 *       authorities updated), otherwise a signed-in player whose token has
 *       expired is refused ({@code TOKEN_EXPIRED}).</li>
 *   <li><b>SUBSCRIBE</b> only to {@code /queue/event/{own game}},
 *       {@code /queue/event/{own game}/{own player}}, {@code /user/queue/errors}
 *       and {@code /queue/heartbeat} ({@code FORBIDDEN_DESTINATION}); a
 *       signed-in player's token must be current (or refreshed by an
 *       {@code Authorization} header on the SUBSCRIBE) and their account not
 *       banned ({@code TOKEN_EXPIRED}, {@code BANNED}).</li>
 * </ul>
 */
@Component
public class StompDestinationGuard implements StompInboundGuard {

    public static final int ORDER = 100;

    static final String APP_PREFIX = "/app/";
    static final String EVENT_QUEUE_PREFIX = "/queue/event/";
    static final String USER_ERRORS = "/user/queue/errors";
    static final String HEARTBEAT = "/queue/heartbeat";

    private final GameAuthorizationPolicy authorizationPolicy;
    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final Clock clock;

    @Autowired
    public StompDestinationGuard(GameAuthorizationPolicy authorizationPolicy,
                                 ObjectProvider<JwtDecoder> jwtDecoder) {
        this(authorizationPolicy, jwtDecoder, Clock.systemUTC());
    }

    StompDestinationGuard(GameAuthorizationPolicy authorizationPolicy,
                          ObjectProvider<JwtDecoder> jwtDecoder, Clock clock) {
        this.authorizationPolicy = authorizationPolicy;
        this.jwtDecoder = jwtDecoder;
        this.clock = clock;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public StompGuardResult check(StompHeaderAccessor accessor, StompPrincipal principal) {
        StompCommand command = accessor.getCommand();
        if (isConnect(accessor) || isPassThrough(accessor)) {
            return StompGuardResult.PASS;
        }
        if (principal == null) {
            throw new StompRejectedException(StompErrorCode.AUTH_REQUIRED, "Connect before sending frames");
        }
        if (command == StompCommand.SEND) {
            checkSend(accessor, principal);
        } else if (command == StompCommand.SUBSCRIBE) {
            checkSubscribe(accessor, principal);
        }
        return StompGuardResult.PASS;
    }

    private void checkSend(StompHeaderAccessor accessor, StompPrincipal principal) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(APP_PREFIX) || isNonCanonical(destination)) {
            throw new StompRejectedException(StompErrorCode.FORBIDDEN_DESTINATION,
                    "SEND is only allowed to /app/** destinations");
        }
        requireMatch(accessor.getFirstNativeHeader(StompConnectAuthenticator.GAME_SESSION_ID),
                principal.getGameSessionId());
        requireMatch(accessor.getFirstNativeHeader(StompConnectAuthenticator.PLAYER_SESSION_ID),
                principal.getPlayerSessionId());

        requireCurrentToken(accessor, principal);
    }

    /**
     * For a signed-in player (auth on): an {@code Authorization} header is a
     * token refresh; otherwise the principal's token must not have expired
     * ({@code TOKEN_EXPIRED}). Guests and auth-off connections carry no token.
     */
    private void requireCurrentToken(StompHeaderAccessor accessor, StompPrincipal principal) {
        if (principal.isGuest() || !authorizationPolicy.isAuthEnabled()) {
            // Guests never carry a token; a JWT on a guest connection is ignored.
            return;
        }
        String header = StompTokens.authorizationHeader(accessor);
        if (header != null) {
            refresh(principal, StompTokens.bearerToken(header));
            return;
        }
        Instant expiresAt = principal.getTokenExpiresAt();
        if (expiresAt != null && clock.instant().isAfter(expiresAt)) {
            throw new StompRejectedException(StompErrorCode.TOKEN_EXPIRED,
                    "Token expired; send a refreshed Authorization header");
        }
    }

    private void refresh(StompPrincipal principal, String token) {
        Jwt jwt = StompTokens.decode(jwtDecoder.getIfAvailable(), token);
        AuthenticatedUser identity = authorizationPolicy.identityOf(jwt);
        if (identity.isService()) {
            throw new StompRejectedException(StompErrorCode.INVALID_CREDENTIALS,
                    "Service tokens cannot act as a player");
        }
        if (principal.getKeycloakId() == null || !principal.getKeycloakId().equals(jwt.getSubject())) {
            throw new StompRejectedException(StompErrorCode.IDENTITY_MISMATCH,
                    "Token does not belong to this player");
        }
        principal.refresh(jwt.getExpiresAt(), identity.getAuthorities());
    }

    private void checkSubscribe(StompHeaderAccessor accessor, StompPrincipal principal) {
        String destination = accessor.getDestination();
        String ownGame = EVENT_QUEUE_PREFIX + principal.getGameSessionId();
        String ownPlayer = ownGame + "/" + principal.getPlayerSessionId();
        boolean allowed = destination != null && !isNonCanonical(destination)
                && (destination.equals(ownGame)
                || destination.equals(ownPlayer)
                || destination.equals(USER_ERRORS)
                || destination.equals(HEARTBEAT));
        if (!allowed) {
            throw new StompRejectedException(StompErrorCode.FORBIDDEN_DESTINATION,
                    "SUBSCRIBE is only allowed to your own game and player queues");
        }
        // A subscription keeps delivering events without any further frame from
        // the client, so it is where an expired token or a ban must stop (G-04).
        // Bans issued after the subscription is made close the socket instead
        // (StompBanEnforcer).
        requireCurrentToken(accessor, principal);
        if (!principal.isGuest() && authorizationPolicy.isSubjectBanned(principal.getKeycloakId())) {
            throw new StompRejectedException(StompErrorCode.BANNED,
                    "Your account is banned and cannot participate in games.");
        }
    }

    private static void requireMatch(String headerValue, String expected) {
        if (headerValue != null && !headerValue.equals(expected)) {
            throw new StompRejectedException(StompErrorCode.IDENTITY_MISMATCH,
                    "Frame identity headers do not match the connected player");
        }
    }

    /**
     * True for a destination that could be routed or matched differently by
     * different pieces of code than its literal string suggests: repeated or
     * trailing slashes, a {@code /./} segment, a backslash or a
     * percent-encoded byte (never legitimate in a destination Spring itself
     * builds), on top of the pre-existing {@code ..} check (G-M4-V1-02).
     */
    static boolean isNonCanonical(String destination) {
        return destination.contains("..")
                || destination.contains("//")
                || destination.contains("/./")
                || destination.endsWith("/.")
                || destination.endsWith("/")
                || destination.contains("\\")
                || destination.contains("%");
    }

    static boolean isConnect(StompHeaderAccessor accessor) {
        StompCommand command = accessor.getCommand();
        return command == StompCommand.CONNECT || command == StompCommand.STOMP;
    }

    /**
     * Frames that need no principal and carry no destination risk: DISCONNECT
     * (including the synthetic one Spring sends when a socket closes),
     * UNSUBSCRIBE and heart-beats.
     */
    static boolean isPassThrough(StompHeaderAccessor accessor) {
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return accessor.getMessageType() == SimpMessageType.HEARTBEAT
                    || accessor.getMessageType() == SimpMessageType.DISCONNECT;
        }
        return command == StompCommand.DISCONNECT || command == StompCommand.UNSUBSCRIBE;
    }
}
