package com.soulsoftworks.sockbowlgame.model.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Settings from clients that predate a field are deserialized via the no-args constructor. */
class TimerSettingsDefaultsTest {
    @Test
    void newTimerFieldsKeepTheirDefaultsWithoutTheBuilder() {
        TimerSettings s = new TimerSettings();
        assertEquals(10, s.getAnswerTimerSeconds());
        assertEquals(6, s.getAdvanceDelaySeconds());
        assertEquals(5, s.getBonusTimerSeconds());
    }
}
