package com.soulsoftworks.sockbowlgame.controller.resolver;

import com.soulsoftworks.sockbowlgame.controller.exception.PlayerVerificationException;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.model.request.PlayerIdentifiers;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class GameSessionInjectionResolver implements HandlerMethodArgumentResolver {

    private final SessionService sessionService;

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

        String gameSessionId = (String) accessor.getHeader("gameSessionId");
        String playerId = (String) accessor.getHeader("playerId");
        String playerSessionSecret = (String) accessor.getHeader("playerSessionSecret");

        Optional<GameSession> gameSessionOptional = Optional.ofNullable(sessionService.getGameSessionById(gameSessionId));

        if(gameSessionOptional.isPresent()){
            GameSession gameSession = gameSessionOptional.get();
            Optional<Player> playerOptional = gameSession.getPlayerList().stream()
                    .filter(p -> p.getPlayerId().equals(playerId)).findFirst();

            if (playerOptional.isPresent()) {
                Player player = playerOptional.get();
                if (player.getPlayerSecret().equals(playerSessionSecret)) {
                    return new GameSessionInjection(new PlayerIdentifiers(playerId, playerSessionSecret), gameSessionId, gameSession);
                } else {
                    throw new PlayerVerificationException("Provided game session secret does not match player's secret.");
                }
            } else {
                throw new PlayerVerificationException("Player is not part of the game session.");
            }
        } else {
            throw new PlayerVerificationException("Game session not found.");
        }
    }


}