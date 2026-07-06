package com.soulsoftworks.sockbowlgame.judge.model;

/**
 * Result of judging one typed guess.
 *
 * @param verdict       ACCEPT / PROMPT / REJECT
 * @param confidence    similarity of the winning match, 0.0–1.0
 * @param matchedAnswer the answer-line entry the guess matched (or "")
 * @param reason        short machine/debug explanation of why
 */
public record JudgeResult(
        Verdict verdict,
        double confidence,
        String matchedAnswer,
        String reason
) {
    public boolean isAccept() {
        return verdict == Verdict.ACCEPT;
    }

    public boolean isPrompt() {
        return verdict == Verdict.PROMPT;
    }
}
