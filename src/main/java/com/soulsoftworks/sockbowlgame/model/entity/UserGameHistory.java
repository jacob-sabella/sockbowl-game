package com.soulsoftworks.sockbowlgame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * UserGameHistory entity representing a user's participation in a game session.
 * Links authenticated users to their game sessions and tracks game outcomes.
 */
@Entity
@Table(name = "user_game_history", indexes = {
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_game_session_id", columnList = "game_session_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserGameHistory {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Reference to the User who participated in the game.
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Game session ID from Redis (may expire after 6 hours).
     */
    @Column(name = "game_session_id", length = 255)
    private String gameSessionId;

    /**
     * Player session ID within the game.
     */
    @Column(name = "player_session_id", length = 255)
    private String playerSessionId;

    /**
     * Timestamp when the user joined the game.
     */
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    /**
     * Final score achieved by the user/team.
     * Null if game is not yet completed.
     */
    @Column(name = "final_score")
    private Integer finalScore;

    /**
     * Name of the team the user was on.
     */
    @Column(name = "team_name", length = 255)
    private String teamName;

    @PrePersist
    protected void onCreate() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }
}
