package com.soulsoftworks.sockbowlgame.judge.model;

/** Outcome of judging a typed answer against a quiz-bowl answer line. */
public enum Verdict {
    /** The guess is correct (exact, accepted alternate, or close fuzzy match). */
    ACCEPT,
    /** The guess is on the right track but not specific enough — ask for more. */
    PROMPT,
    /** The guess is wrong (or explicitly disallowed by the answer line). */
    REJECT
}
