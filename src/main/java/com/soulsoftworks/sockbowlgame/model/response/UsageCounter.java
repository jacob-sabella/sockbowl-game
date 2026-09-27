package com.soulsoftworks.sockbowlgame.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One metric's reading for the admin usage view (plan m4-limits section 2.8,
 * WP-G6). {@code limit} of {@code -1} means unlimited; {@code resetsAt} is an
 * ISO-8601 instant for a daily/global-daily metric, and {@code null} for a
 * concurrent or owned one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageCounter {

    public static final String KIND_CONCURRENT = "concurrent";
    public static final String KIND_DAILY = "daily";
    public static final String KIND_OWNED = "owned";
    public static final String KIND_GLOBAL_DAILY = "global-daily";

    private String metric;
    private long used;
    private long limit;
    private String kind;
    private String resetsAt;
    private boolean overridden;
}
