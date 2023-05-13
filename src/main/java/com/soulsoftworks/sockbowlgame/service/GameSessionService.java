package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.config.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.config.JoinStatus;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
public class GameSessionService {

    private final GameSessionRepository gameSessionRepository;

    public GameSessionService(GameSessionRepository gameSessionRepository) {
        this.gameSessionRepository = gameSessionRepository;
    }

    public GameSession createNewGame(CreateGameRequest createGameRequest) {
        // Create a new join code
        String joinCode = generateJoinCode();

        // Verify that the join code is unique
        while (isGameSessionExistsByJoinCode(joinCode)) {
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
     * For a given JoinGameRequest, find the session with the given join code and create a JoinGameResponse with
     * relevant details
     *
     * @param joinGameRequest
     */
    public JoinGameResponse addPlayerToGameSessionWithJoinCode(JoinGameRequest joinGameRequest) {
        GameSession gameSession = getGameSessionByJoinCode(joinGameRequest.getJoinCode());

        JoinGameResponse joinGameResponse = new JoinGameResponse();

        if (gameSession != null) {
            if (gameSession.getPlayerList().size() == gameSession.getGameSettings().getNumPlayers()) {
                joinGameResponse.setJoinStatus(JoinStatus.SESSION_FULL);
            } else{
                gameSession.addPlayer(joinGameRequest);
                saveGameSession(gameSession);
                joinGameResponse.setJoinStatus(JoinStatus.SUCCESS);
            }
        } else{
            joinGameResponse.setJoinStatus(JoinStatus.GAME_DOES_NOT_EXIST);
            return joinGameResponse;
        }

        if(joinGameResponse.getJoinStatus() == JoinStatus.SUCCESS){
            joinGameResponse.setGameSessionId(gameSession.getId());
        }

        return joinGameResponse;
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

    public boolean isGameSessionExistsByJoinCode(String joinCode) {
        Optional<GameSession> gameSession = gameSessionRepository.findGameSessionByJoinCode(joinCode);
        return gameSession.isPresent();
    }

    private String generateJoinCode() {
        //TODO Replace this with a pool of pre-populated join codes
        return RandomStringUtils.random(4, true, true).toUpperCase(Locale.ROOT);
    }
}

