package com.soulsoftworks.sockbowlgame.redis.service;

import com.soulsoftworks.sockbowlgame.game.model.GameSession;
import com.soulsoftworks.sockbowlgame.redis.repository.GameSessionRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public record GameSessionService(GameSessionRepository gameSessionRepository) {

    public void saveGameSession(GameSession gameSession) {
        gameSessionRepository.save(gameSession);
    }

    public GameSession getGameSessionById(String id) {
        Optional<GameSession> gameSession = gameSessionRepository.findById(id);
        return gameSession.orElse(null);
    }

    public GameSession getGameSessionByJoinCode(String joinCode) {
        Optional<GameSession> gameSession = gameSessionRepository.findGameSessionByJoinCode(joinCode);
        return gameSession.orElse(null);
    }

    public boolean getGameSessionExistsByIdCode(String joinCode) {
        Optional<GameSession> gameSession = gameSessionRepository.findGameSessionByJoinCode(joinCode);
        return gameSession.isPresent();
    }

}
