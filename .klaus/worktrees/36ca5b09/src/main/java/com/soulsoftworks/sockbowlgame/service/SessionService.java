package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.entity.UserGameHistory;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.soulsoftworks.sockbowlgame.model.state.PlayerSettingsByGameMode.*;

@Service
public class SessionService {

    private final GameSessionRepository gameSessionRepository;

    // Optional dependencies - only available when auth is enabled
    @Autowired(required = false)
    private UserRepository userRepository;

    @Autowired(required = false)
    private UserGameHistoryRepository userGameHistoryRepository;

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
            gameSession.getTeamList().add(team);
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

        if (gameSession.getActivePlayerCount() >= playerSettings.getMaxPlayers()) {
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

    /**
     * Add an authenticated user to a game session using a Keycloak JWT token.
     * Creates or updates the User entity, links it to the Player, and records game history.
     *
     * Only available when sockbowl.auth.enabled=true.
     *
     * @param joinGameRequest Join game request from client
     * @param jwt JWT token from Keycloak authentication
     * @return JoinGameResponse with user information
     * @throws IllegalStateException if auth is disabled
     */
    public JoinGameResponse addAuthenticatedUserToGameSession(JoinGameRequest joinGameRequest, Jwt jwt) {
        if (userRepository == null || userGameHistoryRepository == null) {
            throw new IllegalStateException("Authentication is not enabled. Set sockbowl.auth.enabled=true to use this feature.");
        }

        // Extract user info from JWT
        String keycloakId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");

        if (name == null || name.isEmpty()) {
            name = jwt.getClaimAsString("preferred_username");
        }
        if (name == null) {
            name = "User";
        }

        // Find or create user
        String finalName = name;
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .keycloakId(keycloakId)
                            .email(email)
                            .name(finalName)
                            .createdAt(Instant.now())
                            .lastLoginAt(Instant.now())
                            .build();
                    return userRepository.save(newUser);
                });

        // Update last login
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        // Join game session
        GameSession gameSession = getGameSessionByJoinCode(joinGameRequest.getJoinCode());
        PlayerSettings playerSettings = PLAYER_SETTINGS_BY_GAME_MODE.get(
                gameSession.getGameSettings().getGameMode()
        );

        JoinGameResponse response = new JoinGameResponse();
        joinGameRequest.setPlayerSessionId(UUID.randomUUID().toString());

        if (gameSession.getActivePlayerCount() >= playerSettings.getMaxPlayers()) {
            response.setJoinStatus(JoinStatus.SESSION_FULL);
            return response;
        }

        // Create player with user link
        Player player = gameSession.addPlayer(joinGameRequest);
        player.setUserId(user.getId().toString());
        player.setGuest(false);
        player.setName(user.getName());  // Use Keycloak name

        saveGameSession(gameSession);

        // Record in persistent history
        UserGameHistory history = UserGameHistory.builder()
                .userId(user.getId())
                .gameSessionId(gameSession.getId())
                .playerSessionId(player.getPlayerId())
                .joinedAt(Instant.now())
                .build();
        userGameHistoryRepository.save(history);

        response.setJoinStatus(JoinStatus.SUCCESS);
        response.setGameSessionId(gameSession.getId());
        response.setPlayerSessionId(player.getPlayerId());
        response.setPlayerSecret(player.getPlayerSecret());
        response.setUserId(user.getId().toString());

        return response;
    }

    /**
     * Retrieves all active game sessions (sessions with matches currently in progress).
     * Used by GameTimerService to process timers for all active games.
     *
     * @return List of GameSession objects with MatchState.IN_GAME
     */
    public List<GameSession> getAllActiveSessions() {
        return gameSessionRepository.findAll()
                .stream()
                .filter(session -> session.getCurrentMatch() != null &&
                        session.getCurrentMatch().getMatchState() == MatchState.IN_GAME)
                .toList();
    }
}

