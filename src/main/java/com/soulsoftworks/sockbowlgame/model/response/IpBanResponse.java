package com.soulsoftworks.sockbowlgame.model.response;

import com.soulsoftworks.sockbowlgame.model.entity.IpBan;
import lombok.Builder;
import lombok.Data;

/**
 * API representation of an IP ban. Timestamps are ISO-8601 strings
 * ({@code 2026-09-28T12:00:00Z}).
 */
@Data
@Builder
public class IpBanResponse {

    private String id;
    private String cidr;
    private String reason;
    private String bannedBy;
    private String createdAt;
    private String expiresAt;

    public static IpBanResponse fromEntity(IpBan ban) {
        return IpBanResponse.builder()
                .id(ban.getId() == null ? null : ban.getId().toString())
                .cidr(ban.getCidr())
                .reason(ban.getReason())
                .bannedBy(ban.getBannedBy())
                .createdAt(ban.getCreatedAt() == null ? null : ban.getCreatedAt().toString())
                .expiresAt(ban.getExpiresAt() == null ? null : ban.getExpiresAt().toString())
                .build();
    }
}
