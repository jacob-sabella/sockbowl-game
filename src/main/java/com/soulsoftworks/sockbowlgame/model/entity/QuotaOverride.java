package com.soulsoftworks.sockbowlgame.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A per-user, per-metric quota override (D10; plan m4-limits section 2.8):
 * {@code -1} means unlimited. Mirrored to Redis ({@code quota:override:{sub}})
 * by {@code QuotaOverrideService}, which is the only class that writes this
 * table; {@code QuotaService#effectiveLimit} reads the Redis mirror, never
 * this table directly, so Postgres being down never blocks a request.
 *
 * <p>Only mapped when {@code sockbowl.auth.enabled=true} (there is no durable
 * subject to key an override on otherwise), mirroring {@code BanRecord}.
 */
@Entity
@Table(name = "quota_overrides")
@IdClass(QuotaOverrideId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaOverride {

    @Id
    @Column(name = "keycloak_id", updatable = false, nullable = false, length = 255)
    private String keycloakId;

    @Id
    @Column(name = "metric", updatable = false, nullable = false, length = 64)
    private String metric;

    /** {@code -1} means unlimited. */
    @Column(name = "limit_value", nullable = false)
    private long limitValue;

    /** Keycloak subject of the admin who last set this override. */
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
