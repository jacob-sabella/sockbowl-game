package com.soulsoftworks.sockbowlgame.controller.resolver;

import com.soulsoftworks.sockbowlgame.controller.exception.PlayerVerificationException;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.model.request.PlayerIdentifiers;
import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.security.stomp.StompErrorCode;
import com.soulsoftworks.sockbowlgame.security.stomp.StompPrincipal;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;

/**
 * Builds the {@link GameSessionInjection} for WebSocket handlers from the
 * connection's {@link StompPrincipal}, which the inbound interceptor bound at
 * STOMP CONNECT after checking the player secret or JWT (plan m2-auth section
 * 2.5). SEND headers ({@code playerSecret}, {@code Authorization}) are no longer
 * read here: a client cannot switch identity mid-connection.
 *
 * <p>Per message it re-loads the session, confirms the player is still part of
 * it, and re-checks bans for signed-in players. Failures throw
 * {@link PlayerVerificationException} or {@code UserBannedException}, which
 * {@code StompExceptionAdvice} reports on {@code /user/queue/errors} (non-fatal).
 */
@Component
public class GameSessionInjectionResolver implements HandlerMethodArgumentResolver {

    private final SessionService sessionService;
    private final GameAuthorizationPolicy authorizationPolicy;

    public GameSessionInjectionResolver(SessionService sessionService,
                                        GameAuthorizationPolicy authorizationPolicy) {
        this.sessionService = sessionService;
        this.authorizationPolicy = authorizationPolicy;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(GameSessionInjection.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, Message<?> message) {
        Principal user = SimpMessageHeaderAccessor.getUser(message.getHeaders());
        if (!(user instanceof StompPrincipal principal)) {
            throw new PlayerVerificationException(StompErrorCode.AUTH_REQUIRED,
                    "This connection is not authenticated.");
        }

        GameSession gameSession = sessionService.getGameSessionById(principal.getGameSessionId());
        if (gameSession == null) {
            throw new PlayerVerificationException(StompErrorCode.SESSION_NOT_FOUND, "Game session not found.");
        }
        Player player = gameSession.getPlayerById(principal.getPlayerSessionId());
        if (player == null) {
            throw new PlayerVerificationException(StompErrorCode.PLAYER_NOT_IN_SESSION,
                    "Player is not part of the game session.");
        }

        AuthenticatedUser identity = identityOf(principal);
        // Bans can be issued mid-game: re-check on every message (M4-RL-06 caches this).
        authorizationPolicy.ensureNotBanned(identity);

        return new GameSessionInjection(
                new PlayerIdentifiers(principal.getPlayerSessionId(), null),
                principal.getGameSessionId(),
                gameSession,
                identity,
                principal);
    }

    private static AuthenticatedUser identityOf(StompPrincipal principal) {
        if (principal.isGuest() || principal.getKeycloakId() == null) {
            return AuthenticatedUser.guest();
        }
        return AuthenticatedUser.of(principal.getKeycloakId(), null, null,
                List.copyOf(principal.getAuthorities()));
    }
}
