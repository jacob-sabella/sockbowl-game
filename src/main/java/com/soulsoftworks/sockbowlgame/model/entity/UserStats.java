package com.soulsoftworks.sockbowlgame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * UserStats entity representing aggregate statistics for a user.
 * Tracks overall performance metrics across all games.
 */
@Entity
@Table(name = "user_stats", indexes = {
    @Index(name = "idx_user_stats_user_id", columnList = "user_id", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStats {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Reference to the User these stats belong to.
     * Each user has exactly one stats record.
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    /**
     * Total number of games played by the user.
     */
    @Column(name = "total_games", nullable = false)
    @Builder.Default
    private Integer totalGames = 0;

    /**
     * Total number of games won by the user.
     */
    @Column(name = "total_wins", nullable = false)
    @Builder.Default
    private Integer totalWins = 0;

    /**
     * Total number of buzzes (answer attempts) by the user.
     */
    @Column(name = "total_buzzes", nullable = false)
    @Builder.Default
    private Integer totalBuzzes = 0;

    /**
     * Total number of correct buzzes by the user.
     */
    @Column(name = "correct_buzzes", nullable = false)
    @Builder.Default
    private Integer correctBuzzes = 0;

    /**
     * Timestamp when these stats were last updated.
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PreUpdate
    @PrePersist
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
