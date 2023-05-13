package com.soulsoftworks.sockbowlgame.service;

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

    private void processMessage(String message){

    }

    public void sendGameStateToPlayer(String simpSessionId) {
    }
}
