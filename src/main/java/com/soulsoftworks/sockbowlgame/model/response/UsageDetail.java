package com.soulsoftworks.sockbowlgame.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/admin/usage/{sub}} (plan m4-limits section 2.8, WP-G6):
 * the list row's {@link UserUsageSummary}, plus the last-seen IPs, the raw
 * quota overrides, up to 50 recent {@code rl:events} rows for this subject and
 * its currently-active hosted session ids.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageDetail {

    private UserUsageSummary summary;
    private List<String> lastIps;
    private Map<String, Long> overrides;
    private List<UsageEvent> recentEvents;
    private List<String> hostedSessionIds;
}
