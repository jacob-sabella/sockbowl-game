package com.soulsoftworks.sockbowlgame.service;

import java.time.Instant;

/**
 * Published after an admin creates a ban, so live connections of the banned
 * user can be closed (G-04; see
 * {@link com.soulsoftworks.sockbowlgame.security.stomp.StompBanEnforcer}).
 *
 * @param bannedKeycloakId the banned Keycloak subject
 * @param expiresAt        when the ban ends; {@code null} for a permanent ban
 */
public record UserBannedEvent(String bannedKeycloakId, Instant expiresAt) {
}
