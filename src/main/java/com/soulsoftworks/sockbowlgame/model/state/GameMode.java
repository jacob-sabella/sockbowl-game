package com.soulsoftworks.sockbowlgame.model.state;

public enum GameMode {
    QUIZ_BOWL_CLASSIC,
    /** One player vs. the packet; an automated judge replaces the human proctor. */
    SINGLE_PLAYER,
    /** Multiplayer with teams + buzzers, but the answer judge replaces the human proctor. */
    AUTO_PROCTOR;

    /** Modes with no human proctor — the answer judge adjudicates instead. */
    public boolean isProctorless() {
        return this == SINGLE_PLAYER || this == AUTO_PROCTOR;
    }
}
