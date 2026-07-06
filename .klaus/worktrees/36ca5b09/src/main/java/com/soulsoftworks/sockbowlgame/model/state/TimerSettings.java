package com.soulsoftworks.sockbowlgame.model.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration settings for game timers.
 * Controls timer durations and auto-timeout behavior.
 */
@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class TimerSettings {

    /**
     * Duration in seconds for the tossup timer (AWAITING_BUZZ state).
     * Default: 5 seconds
     */
    @Builder.Default
    private int tossupTimerSeconds = 5;

    /**
     * Duration in seconds for the bonus timer (BONUS_AWAITING_ANSWER state).
     * Default: 5 seconds
     */
    @Builder.Default
    private int bonusTimerSeconds = 5;

    /**
     * If true, server automatically triggers timeout when timer expires.
     * If false, server broadcasts countdown but requires manual timeout button click.
     * Default: true
     */
    @Builder.Default
    private boolean autoTimerEnabled = true;
}
