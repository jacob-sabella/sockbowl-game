package com.soulsoftworks.sockbowlgame.controller.resolver;

import com.soulsoftworks.sockbowlgame.controller.exception.PlayerVerificationException;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.model.request.PlayerIdentifiers;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * WebSocket argument resolver that validates player authentication.
 * Supports dual authentication:
 * - Guest mode: validates playerSecret from headers
 * - Authenticated mode: validates JWT token from Keycloak (when auth is enabled)
 */
@Component
public class GameSessionInjectionResolver implements HandlerMethodArgumentResolver {

    private final SessionService sessionService;

    @Value("${sockbowl.auth.enabled:false}")
    private boolean authEnabled;

    // Optional dependency - only available when auth is enabled
    @Autowired(required = false)
    private JwtDecoder jwtDecoder;

    public GameSessionInjectionResolver(SessionService sessionService) {
        this.sessionService = sessionService;
    }


    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(GameSessionInjection.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, Message<?> message) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);

        // Extract headers
        String gameSessionId = accessor.getFirstNativeHeader("gameSessionId");
        String playerId = accessor.getFirstNativeHeader("playerSessionId");
        String playerSessionSecret = accessor.getFirstNativeHeader("playerSecret");
        String authHeader = accessor.getFirstNativeHeader("Authorization");

        // Try JWT authentication first (only if auth is enabled)
        Jwt jwt = null;
        if (authEnabled && jwtDecoder != null && authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                jwt = jwtDecoder.decode(token);
            } catch (Exception e) {
                // Invalid JWT - fall through to guest auth
            }
        }

        Optional<GameSession> gameSessionOptional = Optional.ofNullable(
            sessionService.getGameSessionById(gameSessionId)
        );

        if (gameSessionOptional.isPresent()) {
            GameSession gameSession = gameSessionOptional.get();
            Optional<Player> playerOptional = gameSession.getPlayerList().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst();

            if (playerOptional.isPresent()) {
                Player player = playerOptional.get();
                boolean isValid = false;

                // Validate based on auth type
                if (jwt != null && !player.isGuest()) {
                    // Authenticated user: JWT is valid (already decoded successfully)
                    // Could add additional validation here (e.g., verify userId matches)
                    isValid = true;
                } else if (player.isGuest() && playerSessionSecret != null) {
                    // Guest: verify playerSecret
                    isValid = player.getPlayerSecret().equals(playerSessionSecret);
                } else if (jwt != null && player.isGuest()) {
                    // Authenticated user trying to join guest session
                    throw new PlayerVerificationException(
                        "Cannot use authentication token for guest player session"
                    );
                } else if (!player.isGuest() && jwt == null) {
                    // Guest trying to access authenticated session
                    throw new PlayerVerificationException(
                        "Authentication required for this player session"
                    );
                }

                if (isValid) {
                    return new GameSessionInjection(
                        new PlayerIdentifiers(playerId, playerSessionSecret),
                        gameSessionId,
                        gameSession
                    );
                } else {
                    throw new PlayerVerificationException(
                        "Provided credentials do not match player's credentials."
                    );
                }
            } else {
                throw new PlayerVerificationException(
                    "Player is not part of the game session."
                );
            }
        } else {
            throw new PlayerVerificationException("Game session not found.");
        }
    }


}