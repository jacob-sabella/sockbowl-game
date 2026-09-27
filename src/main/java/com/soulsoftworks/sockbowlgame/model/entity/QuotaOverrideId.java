package com.soulsoftworks.sockbowlgame.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Composite primary key of {@link QuotaOverride}: one override row per
 * (Keycloak subject, metric) pair (plan m4-limits section 2.8's
 * {@code quota_overrides(keycloak_id, metric, ...)} table).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotaOverrideId implements Serializable {

    private String keycloakId;
    private String metric;
}
