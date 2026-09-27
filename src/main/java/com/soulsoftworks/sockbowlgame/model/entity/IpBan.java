package com.soulsoftworks.sockbowlgame.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * An IP/CIDR ban (D8, AB-02; plan m4-limits section 2.4). Unlike a subject ban
 * it always expires ({@code expires_at NOT NULL}, at most
 * {@code sockbowl.ipban.max-ttl} ahead), so a shared address (a school NAT, a
 * carrier-grade NAT) is never collateral-banned for good.
 *
 * <p>Stored in the {@code ip_bans} table of the {@code sockbowl_users}
 * database. Only mapped when {@code sockbowl.auth.enabled=true}.
 */
@Entity
@Table(name = "ip_bans", indexes = {
        @Index(name = "idx_ip_ban_expires_at", columnList = "expires_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IpBan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Canonical CIDR, host bits cleared (a single address is a /32 or /128). */
    @Column(name = "cidr", nullable = false, length = 64)
    private String cidr;

    @Column(name = "reason", length = 1024)
    private String reason;

    /** Keycloak subject of the admin who issued the ban. */
    @Column(name = "banned_by", length = 255)
    private String bannedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isActiveAt(Instant moment) {
        return expiresAt != null && expiresAt.isAfter(moment);
    }
}
