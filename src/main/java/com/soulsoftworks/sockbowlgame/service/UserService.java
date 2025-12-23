package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.entity.UserGameHistory;
import com.soulsoftworks.sockbowlgame.model.entity.UserStats;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.repository.UserStatsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing user data, statistics, and game history.
 * Provides operations for retrieving and updating user-related information.
 *
 * Only active when sockbowl.auth.enabled=true.
 */
@Service
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class UserService {

    private final UserRepository userRepository;
    private final UserStatsRepository userStatsRepository;
    private final UserGameHistoryRepository userGameHistoryRepository;

    public UserService(UserRepository userRepository,
                       UserStatsRepository userStatsRepository,
                       UserGameHistoryRepository userGameHistoryRepository) {
        this.userRepository = userRepository;
        this.userStatsRepository = userStatsRepository;
        this.userGameHistoryRepository = userGameHistoryRepository;
    }

    /**
     * Find a user by their Keycloak ID.
     *
     * @param keycloakId the Keycloak user ID (JWT sub claim)
     * @return Optional containing the user if found
     */
    public Optional<User> findByKeycloakId(String keycloakId) {
        return userRepository.findByKeycloakId(keycloakId);
    }

    /**
     * Get a user by their ID.
     *
     * @param userId the user ID
     * @return Optional containing the user if found
     */
    public Optional<User> getUserById(UUID userId) {
        return userRepository.findById(userId);
    }

    /**
     * Get user statistics, creating a new record if it doesn't exist.
     *
     * @param userId the user ID
     * @return UserStats for the user
     */
    public UserStats getUserStats(UUID userId) {
        return userStatsRepository.findByUserId(userId)
            .orElseGet(() -> {
                UserStats stats = UserStats.builder()
                    .userId(userId)
                    .totalGames(0)
                    .totalWins(0)
                    .totalBuzzes(0)
                    .correctBuzzes(0)
                    .updatedAt(Instant.now())
                    .build();
                return userStatsRepository.save(stats);
            });
    }

    /**
     * Get user game history with pagination.
     *
     * @param userId the user ID
     * @param pageable pagination information
     * @return Page of game history records
     */
    public Page<UserGameHistory> getUserGameHistory(UUID userId, Pageable pageable) {
        return userGameHistoryRepository.findByUserIdOrderByJoinedAtDesc(userId, pageable);
    }

    /**
     * Update user statistics after a game.
     * Increments game count and potentially win count.
     *
     * @param userId the user ID
     * @param won whether the user won the game
     * @param buzzes number of buzzes in the game
     * @param correctBuzzes number of correct buzzes in the game
     */
    public void updateStatsAfterGame(UUID userId, boolean won, int buzzes, int correctBuzzes) {
        UserStats stats = getUserStats(userId);

        stats.setTotalGames(stats.getTotalGames() + 1);
        if (won) {
            stats.setTotalWins(stats.getTotalWins() + 1);
        }
        stats.setTotalBuzzes(stats.getTotalBuzzes() + buzzes);
        stats.setCorrectBuzzes(stats.getCorrectBuzzes() + correctBuzzes);
        stats.setUpdatedAt(Instant.now());

        userStatsRepository.save(stats);
    }

    /**
     * Update game history with final score and team information.
     *
     * @param userId the user ID
     * @param gameSessionId the game session ID
     * @param finalScore the final score
     * @param teamName the team name
     */
    public void updateGameHistoryScore(UUID userId, String gameSessionId, Integer finalScore, String teamName) {
        userGameHistoryRepository.findByUserIdAndGameSessionId(userId, gameSessionId)
            .ifPresent(history -> {
                history.setFinalScore(finalScore);
                history.setTeamName(teamName);
                userGameHistoryRepository.save(history);
            });
    }
}
