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
     * <p>
     * In every view, the proctor's included:
     * <ul>
     *   <li>player credentials and identity and the session owner subject are
     *       removed (AUTH-10);</li>
     *   <li>{@code packet.ownerId} (the packet author's Keycloak subject) is
     *       removed from the current match and every previous match (G2-02);</li>
     *   <li>every previous match is reduced to its public view
     *       ({@link #publicMatchView}): no packet questions or answers, only the
     *       rounds that were played, each as any player may see it (G2-01). The
     *       current proctor may not be the proctor who loaded that packet, so
     *       even the proctor view carries no unplayed question.</li>
     *   <li>the session's {@code proctorsByPacketId} bookkeeping is removed
     *       (R3-G2-03).</li>
     * </ul>
     * Every view but the proctor's also drops the packet id of the current and
     * every previous match (R3-G-01): with the id a player could load the
     * packet as proctor of another game and read its answers there. They keep
     * the packet name.
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

        // Who proctored which packet is server-side bookkeeping (R3-G2-03),
        // and it names packet ids.
        sanitizedGameSession.setProctorsByPacketId(null);

        // Finished matches: public view only, for everyone (G2-01, G2-02).
        sanitizedGameSession.setPreviousMatches(publicMatches(sanitizedGameSession.getPreviousMatches()));

        boolean proctorView = playerMode == PlayerMode.PROCTOR;
        if (!proctorView) {
            // A player who knows a packet's id can load it as proctor of
            // another game and read its answers there (R3-G-01).
            stripPacketIds(sanitizedGameSession.getPreviousMatches());
        }

        Match currentMatch = sanitizedGameSession.getCurrentMatch();
        if (currentMatch == null) {
            return sanitizedGameSession;
        }
        // The packet author's subject is identity, not game data (G2-02).
        stripPacketOwner(currentMatch.getPacket());

        if (!proctorView && currentMatch.getPacket() != null) {

                currentMatch.getPacket().setId(null);
                currentMatch.getPacket().setTossups(null);
                currentMatch.getPacket().setBonuses(null);

                Round currentRound = gameSession.getCurrentMatch().getCurrentRound();
                GameMode gameMode = gameSession.getGameSettings().getGameMode();

                if (currentRound != null && currentRound.getRoundState() != RoundState.COMPLETED) {
                    Round replacement = (gameMode != null && gameMode.isAutoJudgedMultiplayer())
                            ? revealQuestionHideAnswer(sanitizedGameSession.getCurrentRound(), gameMode)
                            : sanitizeRound(sanitizedGameSession.getCurrentRound());
                    currentMatch.setCurrentRound(replacement);
                } else if (currentRound != null) {
                    currentMatch.setCurrentRound(publicRoundView(sanitizedGameSession.getCurrentRound()));
                }
                currentMatch.setPreviousRounds(publicRounds(currentMatch.getPreviousRounds()));
        }

        return sanitizedGameSession;
    }

    /**
     * The view of a finished match that any player may see (G2-01): a match
     * ended early (end-match at round 1, say) still holds its whole packet, so
     * the packet keeps only its metadata (no tossups, no bonuses, no owner
     * subject), every played round is reduced to {@link #publicRoundView}, a
     * round still in progress when the match ended keeps its question and
     * answer only if its tossup had been decided, and a bonus that was never
     * started is dropped. Returns a deep copy; the
     * input is not modified. Null-safe.
     */
    public static Match publicMatchView(Match match) {
        if (match == null) {
            return null;
        }
        Match copy = DeepCopyUtil.deepCopy(match, Match.class);
        if (copy.getPacket() != null) {
            copy.getPacket().setTossups(null);
            copy.getPacket().setBonuses(null);
            stripPacketOwner(copy.getPacket());
        }
        copy.setCurrentRound(finishedRoundView(copy.getCurrentRound()));
        List<Round> previousRounds = copy.getPreviousRounds();
        if (previousRounds != null) {
            List<Round> views = new java.util.ArrayList<>(previousRounds.size());
            for (Round r : previousRounds) {
                views.add(finishedRoundView(r));
            }
            copy.setPreviousRounds(views);
        }
        return copy;
    }

    /**
     * A round of a finished match: {@link #publicRoundView} when its tossup was
     * decided, otherwise nothing of its question or answer. A bonus that was
     * never started is dropped whole (preamble and part questions too), since
     * the same packet can be played again.
     */
    private static Round finishedRoundView(Round round) {
        if (round == null) {
            return null;
        }
        if (!isTossupDecided(round.getRoundState())) {
            Round hidden = sanitizeRound(round);
            hidden.setCurrentBonus(null);
            hidden.setAssociatedBonus(null);
            return hidden;
        }
        Round view = publicRoundView(round);
        if (view.getCurrentBonus() == null) {
            view.setAssociatedBonus(null);
        }
        return view;
    }

    /** {@link #publicMatchView} applied to every match of a list (a new list). Null-safe. */
    public static List<Match> publicMatches(List<Match> matches) {
        if (matches == null) {
            return null;
        }
        List<Match> views = new java.util.ArrayList<>(matches.size());
        for (Match m : matches) {
            views.add(publicMatchView(m));
        }
        return views;
    }

    private static void stripPacketIds(List<Match> matches) {
        if (matches == null) {
            return;
        }
        for (Match m : matches) {
            if (m != null && m.getPacket() != null) {
                m.getPacket().setId(null);
            }
        }
    }

    private static void stripPacketOwner(com.soulsoftworks.sockbowlquestions.models.nodes.Packet packet) {
        if (packet != null) {
            packet.setOwnerId(null);
            packet.setOwnerDisplayName(null);
        }
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

    /**
     * The view of a round that any player may see once its tossup is decided
     * (bonus phase or COMPLETED), G-02:
     * <ul>
     *   <li>the tossup answer is kept only when the tossup is decided (a bonus
     *       state or COMPLETED); otherwise it is cleared;</li>
     *   <li>bonus part answers are kept only for parts already judged: parts
     *       before {@link Round#getCurrentBonusPartIndex()} in reading order, or
     *       every part once the bonus is BONUS_COMPLETED or the round is
     *       COMPLETED;</li>
     *   <li>a bonus that was never played ({@code currentBonus == null}, e.g. a
     *       dead tossup or bonuses turned off) keeps no answers at all.</li>
     * </ul>
     * Returns a deep copy; the input is not modified. Null-safe.
     */
    public static Round publicRoundView(Round round) {
        if (round == null) {
            return null;
        }
        Round copy = DeepCopyUtil.deepCopy(round, Round.class);
        RoundState state = round.getRoundState();
        if (!isTossupDecided(state)) {
            copy.setAnswer("");
        }
        if (copy.getCurrentBonus() == null) {
            // Never played: nothing about it is public.
            hideBonusAnswers(copy.getAssociatedBonus());
            return copy;
        }
        int judged = (state == RoundState.BONUS_COMPLETED || state == RoundState.COMPLETED)
                ? Integer.MAX_VALUE
                : round.getCurrentBonusPartIndex();
        hideBonusAnswersFrom(copy.getCurrentBonus(), judged);
        hideBonusAnswersFrom(copy.getAssociatedBonus(), judged);
        return copy;
    }

    /** {@link #publicRoundView} applied to every round of a list (a new list). Null-safe. */
    public static List<Round> publicRounds(List<Round> rounds) {
        if (rounds == null) {
            return null;
        }
        List<Round> views = new java.util.ArrayList<>(rounds.size());
        for (Round r : rounds) {
            views.add(publicRoundView(r));
        }
        return views;
    }

    private static boolean isTossupDecided(RoundState state) {
        if (state == null) {
            return false;
        }
        return switch (state) {
            case BONUS_PENDING, BONUS_READING_PREAMBLE, BONUS_READING_PART, BONUS_AWAITING_ANSWER,
                 BONUS_COMPLETED, COMPLETED -> true;
            default -> false;
        };
    }

    /**
     * Clear the answers of every part whose reading-order position is
     * {@code >= firstHidden}. Reading order is the part's {@code order}
     * (list position when unset), the same order clients sort parts by.
     */
    private static void hideBonusAnswersFrom(com.soulsoftworks.sockbowlquestions.models.nodes.Bonus bonus,
                                             int firstHidden) {
        if (bonus == null || bonus.getBonusParts() == null) {
            return;
        }
        List<com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart> parts = bonus.getBonusParts();
        List<Integer> byReadingOrder = new java.util.ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            byReadingOrder.add(i);
        }
        byReadingOrder.sort(java.util.Comparator.comparingInt(i -> readingKey(parts, i)));
        for (int rank = 0; rank < byReadingOrder.size(); rank++) {
            var part = parts.get(byReadingOrder.get(rank));
            if (rank >= firstHidden && part != null && part.getBonusPart() != null) {
                part.getBonusPart().setAnswer("");
            }
        }
    }

    private static int readingKey(List<com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart> parts,
                                  int index) {
        var part = parts.get(index);
        return part != null && part.getOrder() != null ? part.getOrder() : index;
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
