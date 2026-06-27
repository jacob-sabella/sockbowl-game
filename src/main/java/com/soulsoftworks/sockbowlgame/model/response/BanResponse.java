package com.soulsoftworks.sockbowlgame.model.response;

import com.soulsoftworks.sockbowlgame.model.entity.BanRecord;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * API representation of a ban record returned to admin clients.
 */
@Data
@Builder
public class BanResponse {

    private String id;
    private String bannedKeycloakId;
    private String reason;
    private String bannedBy;
    private Instant createdAt;
    private Instant expiresAt;

    public static BanResponse fromEntity(BanRecord ban) {
        return BanResponse.builder()
                .id(ban.getId() == null ? null : ban.getId().toString())
                .bannedKeycloakId(ban.getBannedKeycloakId())
                .reason(ban.getReason())
                .bannedBy(ban.getBannedBy())
                .createdAt(ban.getCreatedAt())
                .expiresAt(ban.getExpiresAt())
                .build();
    }
}
