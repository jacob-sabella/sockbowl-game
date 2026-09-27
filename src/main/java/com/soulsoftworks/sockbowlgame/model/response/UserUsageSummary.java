package com.soulsoftworks.sockbowlgame.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One row of {@code GET /api/v1/admin/usage} (plan m4-limits section 2.8,
 * WP-G6, M4-AD-01): a signed-up user enriched with one Redis read and, for the
 * page as a whole, one {@code content-counts} call to sockbowl-questions.
 *
 * <p>{@code tier} and {@code lastSeenAt} come from {@code usage:{sub}:meta}
 * (written by {@code UsageTracker.touch}, WP-G5); a user never yet touched
 * (never made a rate-limited request while signed in) reports tier
 * {@code "unknown"} and a {@code null} {@code lastSeenAt}. {@code packetsOwned}
 * is {@code null} and {@link #isPacketsOwnedUnavailable()} is {@code true} when
 * sockbowl-questions could not be reached.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserUsageSummary {

    private String keycloakId;
    private String username;
    private String displayName;
    private String tier;
    private String lastSeenAt;
    private boolean banned;
    private long activeSessions;
    private Long packetsOwned;
    private boolean packetsOwnedUnavailable;
    private List<UsageCounter> counters;
    private long recentRejections;
}
