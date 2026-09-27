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

        // Strip credentials and identity (AUTH-10) from every player, including the
        // copies held inside each team, and the owner subject from the session.
        // ng only needs player.gameOwner (the boolean), which is kept.
        stripIdentity(sanitizedGameSession.getPlayerList());
        stripTeamIdentity(sanitizedGameSession.getTeamList());
        sanitizedGameSession.setGameOwnerId(null);

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

        // Also hide bonus part answers. associatedBonus is populated at round start
        // (Match.advanceRound), so without this a non-proctor's limited-context round
        // update would carry the upcoming bonus's answers on the wire — readable before
        // the bonus is even asked. Mirrors revealQuestionHideAnswer.
        hideBonusAnswers(sanitizedRound.getCurrentBonus());
        hideBonusAnswers(sanitizedRound.getAssociatedBonus());

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
     * Copies the player list with every player's credentials and identity removed:
     * {@code playerSecret} (the guest STOMP credential), {@code keycloakId} and
     * {@code userId} (AUTH-10). The {@code gameOwner} flag is kept for ng.
     *
     * @param playerList The list of players to sanitize (not modified).
     * @return A sanitized deep copy of the player list.
     */
    public static List<Player> sanitizePlayerList(List<Player> playerList) {
        if (playerList == null) {
            return null;
        }
        List<Player> sanitizedPlayerList = DeepCopyUtil.deepCopy(playerList, new TypeToken<List<Player>>(){}.getType());
        stripIdentity(sanitizedPlayerList);
        return sanitizedPlayerList;
    }

    /**
     * Copies the team list with the same stripping as {@link #sanitizePlayerList}
     * applied to each team's players. Teams hold their own copies of each
     * player (they are serialized separately to Redis), so sanitizing only the
     * session's player list is not enough.
     *
     * @param teamList The list of teams to sanitize (not modified).
     * @return A sanitized deep copy of the team list.
     */
    public static List<Team> sanitizeTeamList(List<Team> teamList) {
        if (teamList == null) {
            return null;
        }
        List<Team> sanitizedTeamList = DeepCopyUtil.deepCopy(teamList, new TypeToken<List<Team>>(){}.getType());
        stripTeamIdentity(sanitizedTeamList);
        return sanitizedTeamList;
    }

    private static void stripTeamIdentity(List<Team> teams) {
        if (teams == null) {
            return;
        }
        teams.forEach(team -> {
            if (team != null) {
                stripIdentity(team.getTeamPlayers());
            }
        });
    }

    private static void stripIdentity(List<Player> players) {
        if (players == null) {
            return;
        }
        players.forEach(player -> {
            if (player != null) {
                player.setPlayerSecret("");
                player.setKeycloakId(null);
                player.setUserId(null);
            }
        });
    }
}
