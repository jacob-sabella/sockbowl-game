package com.soulsoftworks.sockbowlgame.model.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class GameSettings {
    ProctorType proctorType;
    GameMode gameMode;
    boolean bonusesEnabled;

    /**
     * Timer configuration settings for tossup and bonus timers.
     * Includes timer durations and auto-timeout behavior.
     */
    @Builder.Default
    TimerSettings timerSettings = new TimerSettings();

    /** True when the mode has no human proctor (null-safe). */
    public boolean isProctorless() {
        return gameMode != null && gameMode.isProctorless();
    }

    /** True for auto-judged multiplayer modes (AUTO_PROCTOR / FREE_FOR_ALL), null-safe. */
    public boolean isAutoJudgedMultiplayer() {
        return gameMode != null && gameMode.isAutoJudgedMultiplayer();
    }
}
