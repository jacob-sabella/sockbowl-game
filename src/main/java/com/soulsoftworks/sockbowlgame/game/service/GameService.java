package com.soulsoftworks.sockbowlgame.game.service;

import com.soulsoftworks.sockbowlgame.game.model.GameSession;
import com.soulsoftworks.sockbowlgame.game.model.GameSettings;
import com.soulsoftworks.sockbowlgame.redis.service.GameSessionService;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public record GameService(GameSessionService gameSessionService) {

    public void createNewGame() {
        String joinCode = generateJoinCode();

        while (gameSessionService.getGameSessionExistsByIdCode(joinCode) == true) {
            joinCode = generateJoinCode();
        }

        GameSession gameSession = GameSession.builder()
                .gameSettings(new GameSettings())
                .joinCode(joinCode)
                .build();

        gameSessionService.saveGameSession(gameSession);
    }

    private String generateJoinCode() {
        //TODO Replace this with a pool of pre-populated join codes
        return RandomStringUtils.random(4, true, true).toUpperCase(Locale.ROOT);
    }
}
