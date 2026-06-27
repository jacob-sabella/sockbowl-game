package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.entity.BanRecord;
import com.soulsoftworks.sockbowlgame.repository.BanRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service that owns the ban lifecycle. It is the only place that
 * reads/writes {@link BanRecord} state; the authorization policy consults it via
 * {@link #isBanned(String)}.
 *
 * <p>Only active when {@code sockbowl.auth.enabled=true}. When authentication is
 * disabled this bean is absent and the authorization policy treats every user as
 * un-banned.
 */
@Service
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class BanService {

    private final BanRepository banRepository;

    public BanService(BanRepository banRepository) {
        this.banRepository = banRepository;
    }

    /**
     * True when the given Keycloak subject currently has an active ban.
     */
    public boolean isBanned(String keycloakId) {
        return findActiveBan(keycloakId).isPresent();
    }

    /**
     * The active ban for a Keycloak subject, if any.
     */
    public Optional<BanRecord> findActiveBan(String keycloakId) {
        if (keycloakId == null || keycloakId.isBlank()) {
            return Optional.empty();
        }
        return banRepository.findActiveBan(keycloakId, Instant.now());
    }

    /**
     * Create (or refresh) a ban for a user.
     *
     * @param bannedKeycloakId the subject being banned
     * @param reason           human-readable reason
     * @param bannedBy         subject of the issuing admin
     * @param expiresAt        optional expiry; {@code null} for a permanent ban
     */
    public BanRecord createBan(String bannedKeycloakId, String reason, String bannedBy, Instant expiresAt) {
        BanRecord ban = BanRecord.builder()
                .bannedKeycloakId(bannedKeycloakId)
                .reason(reason)
                .bannedBy(bannedBy)
                .createdAt(Instant.now())
                .expiresAt(expiresAt)
                .build();
        return banRepository.save(ban);
    }

    /**
     * List all currently-active bans, newest first.
     */
    public List<BanRecord> listActiveBans() {
        return banRepository.findAllActive(Instant.now());
    }

    /**
     * Remove a ban by its id. Returns {@code true} if a record was removed.
     */
    public boolean removeBan(UUID banId) {
        if (banRepository.existsById(banId)) {
            banRepository.deleteById(banId);
            return true;
        }
        return false;
    }
}
