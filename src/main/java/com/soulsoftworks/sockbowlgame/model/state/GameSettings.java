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
}
