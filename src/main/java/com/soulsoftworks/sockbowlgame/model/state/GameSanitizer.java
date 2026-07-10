package com.soulsoftworks.sockbowlgame.model.state;

import com.google.common.reflect.TypeToken;
import com.soulsoftworks.sockbowlgame.util.DeepCopyUtil;
import com.soulsoftworks.sockbowlgame.util.QuestionTokenizer;

import java.util.List;

public class GameSanitizer {

    private GameSanitizer() {}

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

        if (playerMode != PlayerMode.PROCTOR && sanitizedGameSession.getCurrentMatch().getPacket() != null) {

                sanitizedGameSession.getCurrentMatch().getPacket().setTossups(null);
                sanitizedGameSession.getCurrentMatch().getPacket().setBonuses(null);

                RoundState roundState = gameSession.getCurrentMatch().getCurrentRound().getRoundState();
                GameMode gameMode = gameSession.getGameSettings().getGameMode();

                if (roundState != RoundState.COMPLETED) {
                    Round replacement = (gameMode != null && gameMode.isAutoJudgedMultiplayer())
                            ? revealQuestionHideAnswer(sanitizedGameSession.getCurrentRound(), gameMode)
                            : sanitizeRound(sanitizedGameSession.getCurrentRound());
                    sanitizedGameSession.getCurrentMatch().setCurrentRound(replacement);
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

    /**
     * Single-player view of a round: the player reads the question on screen (there is
     * no proctor reading aloud), so the question stays visible while the answer is hidden
     * until the round completes.
     *
     * @param round the round to copy
     * @return a deep copy with the answer cleared and the question intact
     */
    public static Round revealQuestionHideAnswer(Round round) {
        Round copy = DeepCopyUtil.deepCopy(round, Round.class);
        // BONUS_PENDING: the tossup is over and its result is public (the bonus itself
        // hasn't started yet), so reveal the tossup answer here unlike every other
        // pre-COMPLETED state. Bonus part answers stay hidden via hideBonusAnswers below
        // regardless of round state.
        if (round.getRoundState() != RoundState.BONUS_PENDING) {
            copy.setAnswer("");
        }
        // Also hide bonus part answers so a player can't read them off the wire mid-bonus.
        hideBonusAnswers(copy.getCurrentBonus());
        hideBonusAnswers(copy.getAssociatedBonus());
        return copy;
    }

    /**
     * Auto-judged-multiplayer-aware variant (AUTO_PROCTOR / FREE_FOR_ALL): truncates the
     * question to only what the server has revealed so far (server-authoritative reveal —
     * never leak unrevealed text). Every other mode (including SINGLE_PLAYER) keeps the
     * existing full-text behavior.
     *
     * @param round the round to copy
     * @param mode  the game's mode, used only to decide whether to truncate
     * @return a deep copy with the answer cleared, and — for auto-judged-multiplayer
     *         mid-round — the question truncated to {@code round.getRevealedWordCount()} words
     */
    public static Round revealQuestionHideAnswer(Round round, GameMode mode) {
        Round copy = revealQuestionHideAnswer(round);
        boolean fullyRevealedState = round.getRoundState() == RoundState.COMPLETED
                || round.getRoundState() == RoundState.BONUS_PENDING;
        if (mode != null && mode.isAutoJudgedMultiplayer() && !fullyRevealedState) {
            copy.setQuestion(QuestionTokenizer.truncate(round.getQuestion(), round.getRevealedWordCount()));
        }
        return copy;
    }

    private static void hideBonusAnswers(com.soulsoftworks.sockbowlquestions.models.nodes.Bonus bonus) {
        if (bonus == null || bonus.getBonusParts() == null) {
            return;
        }
        bonus.getBonusParts().forEach(part -> {
            if (part != null && part.getBonusPart() != null) {
                part.getBonusPart().setAnswer("");
            }
        });
    }


    /**
     * Removes the playerSecret from all Player instances in the provided player list.
     * This ensures that sensitive information is not exposed in the player list.
     *
     * @param playerList The list of players whose secrets need to be sanitized.
     * @return A sanitized copy of the player list with playerSecrets removed.
     */
    public static List<Player> sanitizePlayerList(List<Player> playerList) {
        // Create a deep copy of the player list
        List<Player> sanitizedPlayerList = DeepCopyUtil.deepCopy(playerList, new TypeToken<List<Player>>(){}.getType());

        // Remove the playerSecret from each player in the list
        sanitizedPlayerList.forEach(player -> player.setPlayerSecret(""));

        return sanitizedPlayerList;
    }
}
