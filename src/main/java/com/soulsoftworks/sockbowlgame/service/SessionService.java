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
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
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
        return createNewGame(createGameRequest, null);
    }

    /**
     * Create a new game session, optionally owned by an authenticated user.
     *
     * @param createGameRequest the requested game settings
     * @param gameOwnerId       Keycloak subject of the creating user, or null for
     *                          a guest-created session (first-join-wins ownership)
     */
    public GameSession createNewGame(CreateGameRequest createGameRequest, String gameOwnerId) {

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
                .gameOwnerId(gameOwnerId)
                .build();

        // Single player has no proctor to adjudicate bonuses — force them off.
        if (createGameRequest.getGameSettings().getGameMode() == GameMode.SINGLE_PLAYER) {
            gameSession.getGameSettings().setBonusesEnabled(false);
        }

        // Add teams to game session. Free-for-all teams are created one-per-player as
        // players join (see seatFreeForAllJoiner) — the session starts with an empty
        // teamList for that mode.
        if (createGameRequest.getGameSettings().getGameMode() != GameMode.FREE_FOR_ALL) {
            for(int i = 1; i <= playerSettings.getNumTeams(); i++){
                Team team = new Team();
                team.setTeamName("Team " + (i));
                gameSession.getTeamList().add(team);
            }
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
     * @throws ResponseStatusException 404 when no game has that join code
     */
    public JoinGameResponse addPlayerToGameSessionWithJoinCode(JoinGameRequest joinGameRequest) {
        String gameSessionId = requireGameSessionByJoinCode(joinGameRequest.getJoinCode()).getId();

        // Load, add and save under the session's lock (M2R2-LIVE-01). The
        // session is re-read inside the lock, by id: the copy found above may
        // already be stale, and saving it would erase a concurrent writer's
        // change. The join-code search runs once, above, outside any session
        // lock, since it holds the global search lock that stalls every save
        // (R3-G-LOCK).
        return GameSessionLocks.withLock(gameSessionId, () -> {
            GameSession gameSession = requireGameSessionById(gameSessionId);

            PlayerSettings playerSettings = PLAYER_SETTINGS_BY_GAME_MODE.get(gameSession.getGameSettings().getGameMode());

            JoinGameResponse joinGameResponse = new JoinGameResponse();

            //TODO: This isnt permanent solution to player ID
            joinGameRequest.setPlayerSessionId(UUID.randomUUID().toString());

            if (gameSession.getActivePlayerCount() >= playerSettings.getMaxPlayers()) {
                joinGameResponse.setJoinStatus(JoinStatus.SESSION_FULL);
                return joinGameResponse;
            }

            Player newPlayer = gameSession.addPlayer(joinGameRequest);
            seatSinglePlayerJoiner(gameSession, newPlayer);
            if (gameSession.getGameSettings().getGameMode() == GameMode.FREE_FOR_ALL) {
                seatFreeForAllJoiner(gameSession, newPlayer);
            }
            saveGameSession(gameSession);

            joinGameResponse.setJoinStatus(JoinStatus.SUCCESS);
            joinGameResponse.setGameSessionId(gameSession.getId());
            joinGameResponse.setPlayerSessionId(joinGameRequest.getPlayerSessionId());
            joinGameResponse.setPlayerSecret(newPlayer.getPlayerSecret());
            return joinGameResponse;
        });
    }

    /**
     * Single player: seat the lone joiner as the buzzer on the only team, so no
     * team/proctor config step is needed before the match can start. No-op for
     * other game modes.
     */
    private void seatSinglePlayerJoiner(GameSession gameSession, Player player) {
        if (gameSession.getGameSettings().getGameMode() == GameMode.SINGLE_PLAYER
                && !gameSession.getTeamList().isEmpty()) {
            player.setPlayerMode(PlayerMode.BUZZER);
            gameSession.getTeamList().get(0).addPlayerToTeam(player);
        }
    }

    /**
     * Free-for-all: every joiner gets their own one-player team, named after them, so
     * no manual team-pick step is needed before the match can start.
     */
    private void seatFreeForAllJoiner(GameSession gameSession, Player player) {
        // Blank-name joiners fall back to a seat-numbered label ("Player 1",
        // "Player 2", …) so multiple nameless players don't collapse into
        // indistinguishable "Player" teams on the free-for-all scoreboard.
        String teamName = (player.getName() == null || player.getName().isBlank())
                ? "Player " + (gameSession.getTeamList().size() + 1) : player.getName();
        Team team = new Team();
        team.setTeamName(teamName);
        gameSession.getTeamList().add(team);
        player.setPlayerMode(PlayerMode.BUZZER);
        team.addPlayerToTeam(player);
    }

    /**
     * Save the whole session document. Callers that loaded the session to
     * mutate it must hold {@link GameSessionLocks#withLock} for its id from
     * the load through this save, or they can erase a concurrent writer's
     * change (M2R2-LIVE-01).
     */
    public void saveGameSession(GameSession gameSession) {
        GameSessionLocks.duringSave(() -> gameSessionRepository.save(gameSession));
    }

    public GameSession getGameSessionById(String id) {
        Optional<GameSession> gameSession = gameSessionRepository.findById(id);
        return gameSession.orElse(null);
    }

    /**
     * Find a session by join code. The search runs with no save in flight
     * (see {@link GameSessionLocks#duringSearch}): a concurrent save of the
     * same session can make the search come back empty.
     */
    public GameSession getGameSessionByJoinCode(String joinCode) {
        Optional<GameSession> gameSession = GameSessionLocks.duringSearch(
                () -> gameSessionRepository.findGameSessionByJoinCode(joinCode));
        return gameSession.orElse(null);
    }

    /**
     * The session with this join code, or a 404 {@link ResponseStatusException}
     * when the code is blank or unknown (AUTH-17: previously a null session
     * NPE'd into a 500). Both join paths use this before touching any other
     * state.
     */
    public GameSession requireGameSessionByJoinCode(String joinCode) {
        GameSession gameSession = (joinCode == null || joinCode.isBlank())
                ? null
                : getGameSessionByJoinCode(joinCode);
        if (gameSession == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found");
        }
        return gameSession;
    }

    /**
     * The session with this id, or a 404 when it is gone (it expired or was
     * removed between the join-code lookup and taking the session lock).
     */
    private GameSession requireGameSessionById(String gameSessionId) {
        GameSession gameSession = getGameSessionById(gameSessionId);
        if (gameSession == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found");
        }
        return gameSession;
    }

    public boolean isGameSessionExistsByJoinCode(String joinCode) {
        return getGameSessionByJoinCode(joinCode) != null;
    }

    /** Unambiguous uppercase alphabet (no O/0/I/1) for guest join codes. */
    private static final char[] JOIN_CODE_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int JOIN_CODE_LENGTH = 6;
    private static final SecureRandom JOIN_CODE_RANDOM = new SecureRandom();

    private String generateJoinCode() {
        //TODO Replace this with a pool of pre-populated join codes
        StringBuilder code = new StringBuilder(JOIN_CODE_LENGTH);
        for (int i = 0; i < JOIN_CODE_LENGTH; i++) {
            code.append(JOIN_CODE_ALPHABET[JOIN_CODE_RANDOM.nextInt(JOIN_CODE_ALPHABET.length)]);
        }
        return code.toString();
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
     * @throws ResponseStatusException 404 when no game has that join code
     */
    public JoinGameResponse addAuthenticatedUserToGameSession(JoinGameRequest joinGameRequest, Jwt jwt) {
        if (userRepository == null || userGameHistoryRepository == null) {
            throw new IllegalStateException("Authentication is not enabled. Set sockbowl.auth.enabled=true to use this feature.");
        }

        // Resolve the game first so an unknown code is a clean 404 and never
        // creates or touches the User row.
        GameSession gameSession = requireGameSessionByJoinCode(joinGameRequest.getJoinCode());

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

        // Join game session: load (by id, so no join-code search runs under
        // the lock, R3-G-LOCK), add and save under the session's lock
        // (M2R2-LIVE-01), then record history outside it.
        String gameSessionId = gameSession.getId();
        JoinGameResponse response = new JoinGameResponse();
        Player player = GameSessionLocks.withLock(gameSessionId, () -> {
            GameSession current = requireGameSessionById(gameSessionId);
            PlayerSettings playerSettings = PLAYER_SETTINGS_BY_GAME_MODE.get(
                    current.getGameSettings().getGameMode()
            );

            joinGameRequest.setPlayerSessionId(UUID.randomUUID().toString());

            if (current.getActivePlayerCount() >= playerSettings.getMaxPlayers()) {
                return null;
            }

            // Create player with user link
            // addPlayer binds the durable Keycloak identity and decides ownership in
            // one place: the creator of an authenticated session owns it, nobody else.
            Player joined = current.addPlayer(joinGameRequest, keycloakId);
            joined.setUserId(user.getId().toString());
            joined.setName(user.getName());  // Use Keycloak name
            seatSinglePlayerJoiner(current, joined);
            if (current.getGameSettings().getGameMode() == GameMode.FREE_FOR_ALL) {
                seatFreeForAllJoiner(current, joined);
            }

            saveGameSession(current);
            return joined;
        });

        if (player == null) {
            response.setJoinStatus(JoinStatus.SESSION_FULL);
            return response;
        }

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

