package com.soulsoftworks.sockbowlgame.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One rejection recorded by {@code RateLimitEventRecorder} to the
 * {@code rl:events} stream (plan m4-limits section 2.1), as read back by the
 * admin usage API (WP-G6). {@code ts} is an ISO-8601 instant (the stream body
 * carries epoch millis; this is the human-readable form).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageEvent {

    private String id;
    private String ts;
    private String svc;
    private String policy;
    private String kind;
    private String sub;
    private String ip;
    private String path;
}
