package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.JoinStatus;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
public record GameSessionService(GameSessionRepository gameSessionRepository) {

    public GameSession createNewGame(CreateGameRequest createGameRequest) {
        // Create a new join code
        String joinCode = generateJoinCode();

        // Verify that the join code is unique
        while (isGameSessionExistsByIdCode(joinCode)) {
            joinCode = generateJoinCode();
        }

        // Build a new game session
        GameSession gameSession = GameSession.builder()
                .gameSettings(createGameRequest.getGameSettings())
                .joinCode(joinCode)
                .build();

        // Persist game session in Redis
        saveGameSession(gameSession);
        return gameSession;
    }

    /**
     * For a given JoinGameRequest, find the session with the given join code
     * @param joinGameRequest
     */
    public JoinStatus addPlayerToGameSessionWithJoinCode(JoinGameRequest joinGameRequest){
        GameSession gameSession = getGameSessionByJoinCode(joinGameRequest.getJoinCode());

        if(gameSession != null){
            gameSession.addPlayer(joinGameRequest);
            return JoinStatus.SUCCESS;
        }
        return JoinStatus.GAME_DOES_NOT_EXIST;
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

    public boolean isGameSessionExistsByIdCode(String joinCode) {
        Optional<GameSession> gameSession = gameSessionRepository.findGameSessionByJoinCode(joinCode);
        return gameSession.isPresent();
    }

    private String generateJoinCode() {
        //TODO Replace this with a pool of pre-populated join codes
        return RandomStringUtils.random(4, true, true).toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (GameSessionService) obj;
        return Objects.equals(this.gameSessionRepository, that.gameSessionRepository);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gameSessionRepository);
    }

    @Override
    public String toString() {
        return "GameSessionService[" +
                "gameSessionRepository=" + gameSessionRepository + ']';
    }

}
