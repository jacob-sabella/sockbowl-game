package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageQueues;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.ProcessError;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class GameMessageService {

    private final SimpMessagingTemplate simpMessagingTemplate;
    private final GameSessionRepository gameSessionRepository;

    public GameMessageService(SimpMessagingTemplate simpMessagingTemplate, GameSessionRepository gameSessionRepository) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.gameSessionRepository = gameSessionRepository;
    }

    public void sendGameStateToPlayer(GameSessionInjection gameSessionInjection) {
        GameSession gameSession = gameSessionInjection.getGameSession();
        if (gameSession != null) {
            simpMessagingTemplate.convertAndSendToUser(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId(),
                    MessageQueues.GAME_STATE_QUEUE, gameSession);
        } else {
            ProcessError processError = new ProcessError("Game session not found.");
            simpMessagingTemplate.convertAndSendToUser(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId(),
                    MessageQueues.GAME_STATE_QUEUE, processError);
        }
    }
}
