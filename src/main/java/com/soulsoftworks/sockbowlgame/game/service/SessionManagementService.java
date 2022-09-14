package com.soulsoftworks.sockbowlgame.game.service;

import com.soulsoftworks.sockbowlgame.game.model.GameSession;
import com.soulsoftworks.sockbowlgame.game.model.GameSettings;
import com.soulsoftworks.sockbowlgame.redis.repository.GameSessionRepository;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
public record SessionManagementService(GameSessionRepository gameSessionRepository) {

    public GameSession createNewGame() {
        // Create a new join code
        String joinCode = generateJoinCode();

        // Verify that the join code is unique
        while (getGameSessionExistsByIdCode(joinCode)) {
            joinCode = generateJoinCode();
        }

        // Build a new game session
        GameSession gameSession = GameSession.builder()
                .gameSettings(new GameSettings())
                .joinCode(joinCode)
                .build();

        // Persist game session in Redis
        saveGameSession(gameSession);
        return gameSession;
    }

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

    private String generateJoinCode() {
        //TODO Replace this with a pool of pre-populated join codes
        return RandomStringUtils.random(4, true, true).toUpperCase(Locale.ROOT);
    }
}
