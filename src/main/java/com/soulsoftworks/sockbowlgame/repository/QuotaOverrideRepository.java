package com.soulsoftworks.sockbowlgame.repository;

import com.soulsoftworks.sockbowlgame.model.entity.QuotaOverride;
import com.soulsoftworks.sockbowlgame.model.entity.QuotaOverrideId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link QuotaOverride} rows.
 *
 * <p>Only active when {@code sockbowl.auth.enabled=true}, mirroring
 * {@code BanRepository}.
 */
@Repository
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public interface QuotaOverrideRepository extends JpaRepository<QuotaOverride, QuotaOverrideId> {

    List<QuotaOverride> findByKeycloakId(String keycloakId);
}
