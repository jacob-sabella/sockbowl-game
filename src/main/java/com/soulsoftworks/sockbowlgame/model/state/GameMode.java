package com.soulsoftworks.sockbowlgame.model.state;

public enum GameMode {
    QUIZ_BOWL_CLASSIC,
    /** One player vs. the packet; an automated judge replaces the human proctor. */
    SINGLE_PLAYER,
    /** Multiplayer with teams + buzzers, but the answer judge replaces the human proctor. */
    AUTO_PROCTOR,
    /** Multiplayer, one player per team (auto-created on join), same auto-judged flow as AUTO_PROCTOR. */
    FREE_FOR_ALL;

    /** Modes with no human proctor — the answer judge adjudicates instead. */
    public boolean isProctorless() {
        return this == SINGLE_PLAYER || this == AUTO_PROCTOR || this == FREE_FOR_ALL;
    }

    /** Auto-judged multiplayer modes (buzz + judge flow, no human proctor, teams of players). */
    public boolean isAutoJudgedMultiplayer() {
        return this == AUTO_PROCTOR || this == FREE_FOR_ALL;
    }
}
