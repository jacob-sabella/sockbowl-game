package com.soulsoftworks.sockbowlgame.repository;

import com.soulsoftworks.sockbowlgame.model.entity.IpBan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link IpBan} entities. Only active when
 * {@code sockbowl.auth.enabled=true}, like the other user-database repositories.
 */
@Repository
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public interface IpBanRepository extends JpaRepository<IpBan, UUID> {

    /** Every not-yet-expired IP ban, newest first. */
    @Query("SELECT b FROM IpBan b WHERE b.expiresAt > :now ORDER BY b.createdAt DESC")
    List<IpBan> findAllActive(@Param("now") Instant now);
}
