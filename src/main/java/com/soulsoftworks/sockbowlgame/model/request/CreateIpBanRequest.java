package com.soulsoftworks.sockbowlgame.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to ban an IP address or CIDR range (D8, AB-02):
 * {@code {"cidr":"203.0.113.7/32","reason":"...","ttlSeconds":3600}} or, instead
 * of {@code ttlSeconds}, {@code "expiresAt":"2026-09-28T12:00:00Z"}. Exactly one
 * of the two is required; the expiry may be at most
 * {@code sockbowl.ipban.max-ttl} (30 days) away. A bare address means a single
 * host; ranges broader than /16 (IPv4) or /48 (IPv6) are rejected.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateIpBanRequest {

    @NotBlank(message = "cidr cannot be blank")
    @Size(max = 64, message = "cidr is too long")
    private String cidr;

    @Size(max = 1024, message = "reason is too long")
    private String reason;

    /** Seconds until the ban expires. */
    @Positive(message = "ttlSeconds must be positive")
    private Long ttlSeconds;

    /** ISO-8601 instant the ban expires at (alternative to {@code ttlSeconds}). */
    private String expiresAt;
}
