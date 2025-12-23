package com.soulsoftworks.sockbowlgame.repository;

import com.soulsoftworks.sockbowlgame.model.entity.UserGameHistory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for UserGameHistory entity.
 * Provides database access for user game participation history.
 *
 * Only active when sockbowl.auth.enabled=true.
 */
@Repository
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public interface UserGameHistoryRepository extends JpaRepository<UserGameHistory, UUID> {

    /**
     * Find all game history records for a specific user, ordered by join time (newest first).
     *
     * @param userId the user ID
     * @param pageable pagination information
     * @return Page of game history records
     */
    Page<UserGameHistory> findByUserIdOrderByJoinedAtDesc(UUID userId, Pageable pageable);

    /**
     * Find a specific game history record by user ID and game session ID.
     *
     * @param userId the user ID
     * @param gameSessionId the game session ID
     * @return Optional containing the history record if found
     */
    Optional<UserGameHistory> findByUserIdAndGameSessionId(UUID userId, String gameSessionId);
}
