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
 * Persistent ban record keyed by Keycloak subject (the stable identity of a
 * user across sessions). A ban is "active" when it has no expiry or its expiry
 * is in the future.
 *
 * <p>Stored in the {@code bans} table of the {@code sockbowl_users} database.
 * Only mapped when {@code sockbowl.auth.enabled=true}.
 */
@Entity
@Table(name = "bans", indexes = {
        @Index(name = "idx_ban_keycloak_id", columnList = "banned_keycloak_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BanRecord {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Keycloak subject (sub claim) of the banned user.
     */
    @Column(name = "banned_keycloak_id", nullable = false, length = 255)
    private String bannedKeycloakId;

    /**
     * Human-readable reason for the ban.
     */
    @Column(name = "reason", length = 1024)
    private String reason;

    /**
     * Keycloak subject of the admin who issued the ban.
     */
    @Column(name = "banned_by", length = 255)
    private String bannedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Optional expiry. {@code null} means a permanent ban.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * A ban is active when it is permanent (no expiry) or expires in the future.
     */
    public boolean isActiveAt(Instant moment) {
        return expiresAt == null || expiresAt.isAfter(moment);
    }
}
