package com.soulsoftworks.sockbowlgame.model.state;

import lombok.Data;

/**
 * Represents an answer to a single bonus part (question).
 * Tracks which part (0-2) and whether it was answered correctly.
 */
@Data
public class BonusPartAnswer {
    private int partIndex;  // 0 to (number of bonus parts - 1); a bonus has 1 to 6 parts
    private boolean correct;
    /** What the team typed, in auto-judged modes (null when a proctor judged it aloud). */
    private String answerText;
}
