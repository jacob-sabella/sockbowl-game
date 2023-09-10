package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.soulsoftworks.sockbowlgame.model.state.PlayerSettingsByGameMode.*;

@Service
public class SessionService {

    private final GameSessionRepository gameSessionRepository;

    public SessionService(GameSessionRepository gameSessionRepository) {
        this.gameSessionRepository = gameSessionRepository;
    }

    public GameSession createNewGame(CreateGameRequest createGameRequest) {

        // Get the player settings for the game mode
        PlayerSettings playerSettings = PLAYER_SETTINGS_BY_GAME_MODE.get(createGameRequest.getGameSettings().getGameMode());

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

        // Add teams to game session
        for(int i = 1; i <= playerSettings.getNumTeams(); i++){
            Team team = new Team();
            team.setTeamName("Team " + (i));
            gameSession.getTeams().add(team);
        }

        // Persist game session in Redis
        saveGameSession(gameSession);
        return gameSession;
    }

    /**
     * For a given JoinGameRequest, find the session with the given join code and create a JoinGameResponse with
     * relevant details
     *
     * @param joinGameRequest Join game request from client
     */
    public JoinGameResponse addPlayerToGameSessionWithJoinCode(JoinGameRequest joinGameRequest) {
        Player newPlayer = null;

        GameSession gameSession = getGameSessionByJoinCode(joinGameRequest.getJoinCode());

        PlayerSettings playerSettings = PLAYER_SETTINGS_BY_GAME_MODE.get(gameSession.getGameSettings().getGameMode());

        JoinGameResponse joinGameResponse = new JoinGameResponse();

        //TODO: This isnt permanent solution to player ID
        joinGameRequest.setPlayerSessionId(UUID.randomUUID().toString());

        if (gameSession.getPlayerList().size() == playerSettings.getMaxPlayers()) {
            joinGameResponse.setJoinStatus(JoinStatus.SESSION_FULL);
        } else {
            newPlayer = gameSession.addPlayer(joinGameRequest);
            saveGameSession(gameSession);
            joinGameResponse.setJoinStatus(JoinStatus.SUCCESS);
        }

        if(joinGameResponse.getJoinStatus() == JoinStatus.SUCCESS && newPlayer != null){
            joinGameResponse.setGameSessionId(gameSession.getId());
            joinGameResponse.setPlayerSessionId(joinGameRequest.getPlayerSessionId());
            joinGameResponse.setPlayerSecret(newPlayer.getPlayerSecret());
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

