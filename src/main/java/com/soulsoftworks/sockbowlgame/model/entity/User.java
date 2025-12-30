package com.soulsoftworks.sockbowlgame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * User entity representing authenticated users in the system.
 * Linked to Keycloak for authentication and authorization.
 * Stores persistent user information beyond game sessions.
 */
@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Keycloak user ID (sub claim from JWT).
     * This is the unique identifier from Keycloak.
     */
    @Column(name = "keycloak_id", nullable = false, unique = true, length = 255)
    private String keycloakId;

    /**
     * User email address from Keycloak.
     */
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /**
     * User display name from Keycloak.
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Timestamp when the user was first created.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp of the user's last login.
     */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
