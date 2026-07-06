package com.soulsoftworks.sockbowlgame.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Records that a user has seen a specific source question (by its origin id,
 * e.g. a qbreader {@code _id}). Used to avoid repeating questions when a
 * logged-in user generates new random packets.
 */
@Entity
@Table(name = "user_used_question", indexes = {
    @Index(name = "idx_uuq_user_id", columnList = "user_id"),
    @Index(name = "uq_uuq_user_remote", columnList = "user_id,remote_id", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserUsedQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The origin question id (qbreader {@code _id}). */
    @Column(name = "remote_id", nullable = false)
    private String remoteId;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;

    @PrePersist
    void prePersist() {
        if (seenAt == null) {
            seenAt = Instant.now();
        }
    }
}
