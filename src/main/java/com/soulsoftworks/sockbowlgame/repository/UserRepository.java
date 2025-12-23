package com.soulsoftworks.sockbowlgame.repository;

import com.soulsoftworks.sockbowlgame.model.entity.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for User entity.
 * Provides database access for authenticated user accounts.
 *
 * Only active when sockbowl.auth.enabled=true.
 */
@Repository
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Find a user by their Keycloak ID (JWT sub claim).
     *
     * @param keycloakId the Keycloak user ID
     * @return Optional containing the user if found
     */
    Optional<User> findByKeycloakId(String keycloakId);

    /**
     * Find a user by their email address.
     *
     * @param email the user's email
     * @return Optional containing the user if found
     */
    Optional<User> findByEmail(String email);
}
