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
     * Default: 8 seconds
     */
    @Builder.Default
    private int tossupTimerSeconds = 8;

    /**
     * Duration in seconds for the bonus timer (BONUS_AWAITING_ANSWER state).
     * Default: 15 seconds (time to confer and type)
     */
    @Builder.Default
    private int bonusTimerSeconds = 15;

    /**
     * If true, server automatically triggers timeout when timer expires.
     * If false, server broadcasts countdown but requires manual timeout button click.
     * Default: true
     */
    @Builder.Default
    private boolean autoTimerEnabled = true;

    /**
     * Words revealed per second during the AUTO_PROCTOR server-driven reading pace.
     * Host-set, applies to every player identically. Range: 1..10.
     * Default: 3 (about 180 words a minute, a moderator's pace)
     */
    @Builder.Default
    private int readingWordsPerSecond = 3;

    /**
     * Auto-judged multiplayer (AUTO_PROCTOR / FREE_FOR_ALL): seconds the buzzed-in
     * player has to submit an answer before the server marks it wrong. Server-driven,
     * identical for every player.
     * Default: 15 seconds
     */
    @Builder.Default
    private int answerTimerSeconds = 15;

    /**
     * Auto-judged multiplayer: seconds the result of a finished round stays up
     * before the server advances to the next tossup on its own, and likewise the
     * pause on a won tossup before the server starts its bonus (players can
     * still start either sooner).
     * Default: 10 seconds
     */
    @Builder.Default
    private int advanceDelaySeconds = 10;
}
