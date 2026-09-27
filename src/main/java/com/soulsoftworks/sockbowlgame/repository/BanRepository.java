package com.soulsoftworks.sockbowlgame.repository;

import com.soulsoftworks.sockbowlgame.model.entity.BanRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link BanRecord} entities.
 *
 * <p>Only active when {@code sockbowl.auth.enabled=true}, mirroring the User
 * persistence layer.
 */
@Repository
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public interface BanRepository extends JpaRepository<BanRecord, UUID> {

    /**
     * Find an active ban (permanent or not-yet-expired) for a given Keycloak
     * subject, if one exists. A subject can hold several active bans (an admin
     * may ban twice), so this picks the first of {@link #findActiveBans}
     * rather than failing on a non-unique result.
     */
    default Optional<BanRecord> findActiveBan(String keycloakId, Instant now) {
        return findActiveBans(keycloakId, now).stream().findFirst();
    }

    /**
     * Every active ban for a Keycloak subject, newest first.
     */
    @Query("SELECT b FROM BanRecord b WHERE b.bannedKeycloakId = :keycloakId "
            + "AND (b.expiresAt IS NULL OR b.expiresAt > :now) ORDER BY b.createdAt DESC")
    List<BanRecord> findActiveBans(@Param("keycloakId") String keycloakId,
                                   @Param("now") Instant now);

    /**
     * All currently-active bans, newest first.
     */
    @Query("SELECT b FROM BanRecord b WHERE b.expiresAt IS NULL OR b.expiresAt > :now "
            + "ORDER BY b.createdAt DESC")
    List<BanRecord> findAllActive(@Param("now") Instant now);
}
