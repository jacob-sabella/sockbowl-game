package com.soulsoftworks.sockbowlgame.model.state;

import lombok.Data;

@Data
public class Buzz {
    private String playerId;
    private String teamId;
    private boolean correct;
    /** What the player typed, in auto-judged modes (null when a proctor judged it aloud). */
    private String answerText;
}
