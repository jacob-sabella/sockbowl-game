package com.soulsoftworks.sockbowlgame.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/admin/usage/{sub}/reset} (plan m4-limits section 2.8):
 * {@code {"metric": "ai.generations"}} resets just that counter; an absent or
 * blank {@code metric} resets every one of this subject's today's counters
 * ({@code hosted-sessions}, {@code ai.generations}, {@code imports}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetUsageRequest {

    private String metric;
}
