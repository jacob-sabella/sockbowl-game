package com.soulsoftworks.sockbowlgame.repository;

import com.soulsoftworks.sockbowlgame.model.entity.UserStats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for UserStats entity.
 * Provides database access for user aggregate statistics.
 *
 * Only active when sockbowl.auth.enabled=true.
 */
@Repository
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public interface UserStatsRepository extends JpaRepository<UserStats, UUID> {

    /**
     * Find stats for a specific user.
     * Each user should have exactly one stats record.
     *
     * @param userId the user ID
     * @return Optional containing the stats if found
     */
    Optional<UserStats> findByUserId(UUID userId);
}
