package com.soulsoftworks.sockbowlgame.model.state;

import lombok.Data;

/**
 * Represents an answer to a single bonus part (question).
 * Tracks which part (0-2) and whether it was answered correctly.
 */
@Data
public class BonusPartAnswer {
    private int partIndex;  // 0, 1, or 2
    private boolean correct;
}
