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
 *   <li><b>SEND</b> only to {@code /app/**}, never straight to a broker
 *       destination ({@code FORBIDDEN_DESTINATION}); {@code gameSessionId} /
 *       {@code playerSessionId} headers, when present, must be the principal's
 *       ({@code IDENTITY_MISMATCH}); an {@code Authorization} header on a signed-in
 *       player's SEND is a token refresh (decoded, same {@code sub}, expiry and
 *       authorities updated), otherwise a signed-in player whose token has
 *       expired is refused ({@code TOKEN_EXPIRED}).</li>
 *   <li><b>SUBSCRIBE</b> only to {@code /queue/event/{own game}},
 *       {@code /queue/event/{own game}/{own player}}, {@code /user/queue/errors}
 *       and {@code /queue/heartbeat} ({@code FORBIDDEN_DESTINATION}).</li>
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
        if (destination == null || !destination.startsWith(APP_PREFIX) || destination.contains("..")) {
            throw new StompRejectedException(StompErrorCode.FORBIDDEN_DESTINATION,
                    "SEND is only allowed to /app/** destinations");
        }
        requireMatch(accessor.getFirstNativeHeader(StompConnectAuthenticator.GAME_SESSION_ID),
                principal.getGameSessionId());
        requireMatch(accessor.getFirstNativeHeader(StompConnectAuthenticator.PLAYER_SESSION_ID),
                principal.getPlayerSessionId());

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
        boolean allowed = destination != null
                && (destination.equals(ownGame)
                || destination.equals(ownPlayer)
                || destination.equals(USER_ERRORS)
                || destination.equals(HEARTBEAT));
        if (!allowed) {
            throw new StompRejectedException(StompErrorCode.FORBIDDEN_DESTINATION,
                    "SUBSCRIBE is only allowed to your own game and player queues");
        }
    }

    private static void requireMatch(String headerValue, String expected) {
        if (headerValue != null && !headerValue.equals(expected)) {
            throw new StompRejectedException(StompErrorCode.IDENTITY_MISMATCH,
                    "Frame identity headers do not match the connected player");
        }
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
