package com.soulsoftworks.sockbowlgame.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Request payload for an admin to ban a user by their Keycloak subject.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateBanRequest {

    @NotBlank(message = "bannedKeycloakId cannot be blank")
    private String bannedKeycloakId;

    private String reason;

    /**
     * Optional expiry. Null means a permanent ban.
     */
    private Instant expiresAt;
}
