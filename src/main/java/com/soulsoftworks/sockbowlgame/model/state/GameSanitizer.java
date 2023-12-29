package com.soulsoftworks.sockbowlgame.model.state;

import com.soulsoftworks.sockbowlgame.util.DeepCopyUtil;

public class GameSanitizer {

    /**
     * Sanitizes the given game session based on the specified player mode.
     * This method creates a deep copy of the provided game session and then modifies
     * it to ensure privacy and integrity based on the player mode. For example, if the
     * player mode is not PROCTOR, it removes sensitive data such as toss-ups, bonuses,
     * and current round questions and answers.
     *
     * @param gameSession The original game session to be sanitized.
     * @param playerMode  The player mode determining the level of sanitization.
     * @return A sanitized copy of the original game session.
     */
    public static GameSession sanitizeGameSession(GameSession gameSession, PlayerMode playerMode) {
        // Create a deep copy of the game session
        GameSession sanitizedGameSession = DeepCopyUtil.deepCopy(gameSession, GameSession.class);

        // Now modify the sanitizedGameSession as needed
        sanitizedGameSession.getPlayerList().forEach(player -> player.setPlayerSecret(""));

        if (playerMode != PlayerMode.PROCTOR) {
            if (sanitizedGameSession.getCurrentMatch().getPacket() != null) {
                sanitizedGameSession.getCurrentMatch().getPacket().setTossups(null);
                sanitizedGameSession.getCurrentMatch().getPacket().setBonuses(null);
                sanitizedGameSession.getCurrentMatch().setCurrentRound(sanitizeRound(sanitizedGameSession.getCurrentRound()));
            }
        }

        return sanitizedGameSession;
    }

    /**
     * Sanitizes a round by removing its question and answer data.
     * This method creates a deep copy of the provided round and clears
     * its question and answer fields. This is useful in scenarios where
     * the structure of the round is required without exposing its content.
     *
     * @param round The round to be sanitized.
     * @return A sanitized copy of the round.
     */
    public static Round sanitizeRound(Round round) {
        // Create a deep copy of the round
        Round sanitizedRound = DeepCopyUtil.deepCopy(round, Round.class);

        // Remove the question and answer data
        sanitizedRound.setQuestion("");
        sanitizedRound.setAnswer("");

        return sanitizedRound;
    }
}
