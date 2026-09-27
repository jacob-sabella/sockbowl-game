package com.soulsoftworks.sockbowlgame.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * {@code GET /api/v1/admin/usage/global} (plan m4-limits section 2.8, WP-G6):
 * the fleet-wide numbers the admin usage view's global card shows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalUsage {

    private UsageCounter aiServerKey;
    private long activeHostedSessions;
    private List<GuestIpCount> topGuestIps;
    private long rejectionsLastHour;
}
