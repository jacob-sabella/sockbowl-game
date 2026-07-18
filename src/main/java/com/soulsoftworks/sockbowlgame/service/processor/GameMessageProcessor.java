package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.*;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.AdvanceRound;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.AnswerUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.BonusUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.PlayerBuzzed;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.RoundUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameSessionUpdate;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.judge.AnswerJudgeService;
import com.soulsoftworks.sockbowlgame.judge.model.JudgeResult;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.soulsoftworks.sockbowlgame.model.state.GameSanitizer.sanitizeRound;

/**
 * Service class responsible for processing game-related socket messages.
 */
@Service
public class GameMessageProcessor extends MessageProcessor {
    /**
     * Method to initialize the mapping for the processor.
     */
    /** Automated proctor for single-player mode. Stateless; safe to share. */
    private final AnswerJudgeService answerJudgeService = new AnswerJudgeService();

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(PlayerIncomingBuzz.class, this::playerBuzz);
        processorMapping.registerProcessor(AnswerOutcome.class, this::playerAnswer);
        processorMapping.registerProcessor(SubmitAnswer.class, this::playerSubmitAnswer);
        processorMapping.registerProcessor(BonusPartOutcome.class, this::bonusPartAnswer);
        processorMapping.registerProcessor(TimeoutRound.class, this::timeout);
        processorMapping.registerProcessor(FinishedReading.class, this::finishedReading);
        processorMapping.registerProcessor(AdvanceRound.class, this::advanceRound);
        processorMapping.registerProcessor(FinishedReadingBonusPreamble.class, this::finishedReadingBonusPreamble);
        processorMapping.registerProcessor(FinishedReadingBonusPart.class, this::finishedReadingBonusPart);
        processorMapping.registerProcessor(TimeoutBonusPart.class, this::timeoutBonusPart);
        processorMapping.registerProcessor(StartBonus.class, this::startBonus);
    }


    /**
     * Processes the player's buzz action in the game.
     * This method checks the player's mode, the current round state, and whether the team has already buzzed.
     * If all conditions are met, it processes the buzz and returns an update message.
     * Otherwise, it returns an error message.
     *
     * @param playerBuzz The incoming buzz action message from the player.
     * @return SockbowlOutMessage containing either the result of the buzz processing or an error message.
     */
    public SockbowlOutMessage playerBuzz(SockbowlInMessage playerBuzz) {
        // Retrieve the current game session from the player's buzz
        GameSession gameSession = playerBuzz.getGameSession();

        // Single player has no proctor — it uses SubmitAnswer, not a bare buzz.
        if (gameSession.getGameSettings().getGameMode() == GameMode.SINGLE_PLAYER) {
            return ProcessError.accessDeniedMessage(playerBuzz);
        }

        // Check if the player is in the BUZZER mode, if not return an error message.
        // This must precede the team lookup below: a proctor (removed from every team)
        // or a spectator has no team, so dereferencing getTeamByPlayerId() first would
        // NPE instead of returning this clean error.
        Player buzzingPlayer = gameSession.getPlayerById(playerBuzz.getOriginatingPlayerId());
        if (buzzingPlayer == null || buzzingPlayer.getPlayerMode() != PlayerMode.BUZZER) {
            return ProcessError.builder()
                    .recipient(playerBuzz.getOriginatingPlayerId())
                    .error("Player mode is not buzzer")
                    .build();
        }

        // Get the teamId of the player who buzzed
        Team buzzingTeam = gameSession.getTeamByPlayerId(playerBuzz.getOriginatingPlayerId());
        if (buzzingTeam == null) {
            return ProcessError.builder()
                    .recipient(playerBuzz.getOriginatingPlayerId())
                    .error("Player is not on a team")
                    .build();
        }
        String teamId = buzzingTeam.getTeamId();

        // Check if the team has already buzzed this round, if so return an error message
        if (gameSession.getCurrentRound().hasTeamBuzzed(teamId)) {
            return ProcessError.builder()
                    .recipient(playerBuzz.getOriginatingPlayerId())
                    .error("Team has already buzzed in this round")
                    .build();
        }

        // Check if the current game round is in a state that allows buzzing, if not return an error message
        if (gameSession.getCurrentRound().getRoundState() != RoundState.PROCTOR_READING &&
                gameSession.getCurrentRound().getRoundState() != RoundState.AWAITING_BUZZ) {
            return ProcessError.builder()
                    .recipient(playerBuzz.getOriginatingPlayerId())
                    .error("Buzz processed when round is in unsupported state")
                    .build();
        }

        // Process the player's buzz action, update the current game round state
        gameSession.getCurrentRound().processBuzz(
                playerBuzz.getOriginatingPlayerId(),
                gameSession.getTeamByPlayerId(playerBuzz.getOriginatingPlayerId()).getTeamId()
        );

        // Clear tossup timer (player buzzed)
        gameSession.getCurrentRound().clearTossupTimer();

        // Auto-proctor: no proctor to receive a full-context copy — broadcast the buzz to
        // everyone with the question visible and the answer hidden.
        if (gameSession.getGameSettings().isAutoJudgedMultiplayer()) {
            PlayerBuzzed buzzed = PlayerBuzzed.builder()
                    .playerId(playerBuzz.getOriginatingPlayerId())
                    .teamId(teamId)
                    .round(GameSanitizer.revealQuestionHideAnswer(gameSession.getCurrentRound(), gameSession.getGameSettings().getGameMode()))
                    .build();
            return SockbowlMultiOutMessage.builder().sockbowlOutMessage(buzzed).build();
        }

        // Create round update messages
        PlayerBuzzed fullContextPlayerBuzzed = PlayerBuzzed.builder()
                .playerId(playerBuzz.getOriginatingPlayerId())
                .teamId(gameSession.getTeamByPlayerId(playerBuzz.getOriginatingPlayerId()).getTeamId())
                .round(gameSession.getCurrentRound())
                .recipient(gameSession.getProctor().getPlayerId())
                .build();

        PlayerBuzzed limitedContextPlayerBuzzed = PlayerBuzzed.builder()
                .playerId(playerBuzz.getOriginatingPlayerId())
                .teamId(gameSession.getTeamByPlayerId(playerBuzz.getOriginatingPlayerId()).getTeamId())
                .round(sanitizeRound(gameSession.getCurrentRound()))
                .recipients(gameSession.getPlayerList().stream()
                        .map(Player::getPlayerId)
                        .filter(playerId -> !playerId.equals(gameSession.getProctor().getPlayerId()))
                        .toList())
                .build();

        // Return player buzz message to all players
        return SockbowlMultiOutMessage
                .builder()
                .sockbowlOutMessage(fullContextPlayerBuzzed)
                .sockbowlOutMessage(limitedContextPlayerBuzzed)
                .build();
    }

    /**
     * Processes a player's answer (correct or incorrect) based on the current game session.
     * It validates the player's role as the proctor and the current round state.
     * Depending on the answer type, it processes the answer and advances the round if necessary,
     * returning a corresponding message.
     *
     * @param answer The incoming answer message containing details about the player's answer.
     * @return SockbowlOutMessage representing the outcome (CorrectAnswer, AnswerUpdate, or ProcessError).
     * @throws NullPointerException     if the answer or its nested objects are null.
     * @throws IllegalArgumentException if the game session state or the answer type is invalid.
     */
    public SockbowlOutMessage playerAnswer(SockbowlInMessage answer) {

        AnswerOutcome answerOutcome = (AnswerOutcome) answer;

        // Retrieve the current game session from message
        GameSession gameSession = answer.getGameSession();

        // Check if the player is the proctor, if not return an error message
        if (gameSession.getPlayerModeById(answer.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
            return ProcessError.accessDeniedMessage(answer);
        }

        // We can only process this type of message if we're waiting for a buzz
        if (gameSession.getCurrentRound().getRoundState() != RoundState.AWAITING_ANSWER) {
            return ProcessError.builder()
                    .recipient(answer.getOriginatingPlayerId())
                    .error("Answer incorrect message processed when round is in unsupported state")
                    .build();
        }

        // Depending on the answer outcome, create different messages with sanitized round data
        if (!answerOutcome.isCorrect()) {
            gameSession.getCurrentRound().processIncorrectAnswer();
            boolean shouldCompleteRound = gameSession.getTeamList().stream()
                    .allMatch(team -> gameSession.getCurrentRound().getBuzzList().stream()
                            .filter(buzz -> buzz.getTeamId().equals(team.getTeamId()))
                            .anyMatch(buzz -> !buzz.isCorrect()));

            if (shouldCompleteRound) {
                gameSession.getCurrentMatch().completeRound();
            } else {
                // Restart tossup timer if returning to AWAITING_BUZZ
                if (gameSession.getCurrentRound().getRoundState() == RoundState.AWAITING_BUZZ) {
                    int timerDuration = gameSession.getGameSettings().getTimerSettings().getTossupTimerSeconds();
                    gameSession.getCurrentRound().startTossupTimer(timerDuration);
                }
            }
        } else {
            // Process correct answer
            gameSession.getCurrentRound().processCorrectAnswer();

            // Check if bonuses are enabled and if this tossup has a bonus
            if (gameSession.getGameSettings().isBonusesEnabled() &&
                gameSession.getCurrentRound().hasAssociatedBonus()) {
                // Start bonus phase for the team that answered correctly
                String teamId = gameSession.getCurrentRound().getBuzzList()
                        .stream()
                        .filter(buzz -> buzz.isCorrect())
                        .findFirst()
                        .map(buzz -> buzz.getTeamId())
                        .orElse(null);

                if (teamId != null) {
                    gameSession.getCurrentMatch().startBonusPhase(teamId);
                }
            } else {
                // No bonus, mark round as completed
                gameSession.getCurrentMatch().completeRound();
            }
        }

        // Return multi-message with both full and limited context messages
        return createAnswerUpdateMessages(gameSession, answerOutcome.isCorrect());

    }


    /**
     * Processes a timeout event in a game session.
     * This method is called when a timeout occurs and checks if the current round state allows for a timeout.
     * If valid, it advances the round and returns round update messages.
     * Otherwise, returns a ProcessError message.
     *
     * @param timeoutMessage The incoming timeout message with game session details.
     * @return SockbowlOutMessage containing round updates or a ProcessError message.
     * @throws NullPointerException if the timeoutMessage or its nested objects are null.
     */
    /**
     * Processes a single-player typed answer: the lone player buzzes and answers in one
     * step, and the {@link AnswerJudgeService} adjudicates instead of a human proctor.
     * Valid only in {@link GameMode#SINGLE_PLAYER}. One guess resolves the tossup — an
     * ACCEPT scores it correct, anything else (PROMPT/REJECT) marks it incorrect — then
     * the round completes and the full round (answer included) is revealed to the player.
     *
     * @param message the incoming {@link SubmitAnswer}
     * @return an AnswerUpdate broadcast, or a ProcessError
     */
    public SockbowlOutMessage playerSubmitAnswer(SockbowlInMessage message) {
        SubmitAnswer submitAnswer = (SubmitAnswer) message;
        GameSession gameSession = message.getGameSession();

        GameMode mode = gameSession.getGameSettings().getGameMode();
        // Auto-proctor is multiplayer: the player buzzed first (PlayerIncomingBuzz), then
        // submits an answer the judge adjudicates with the same wrong/right flow as a proctor.
        if (mode != null && mode.isAutoJudgedMultiplayer()) {
            return autoProctorSubmit(gameSession, submitAnswer);
        }
        // Otherwise this message is single-player only.
        if (mode != GameMode.SINGLE_PLAYER) {
            return ProcessError.accessDeniedMessage(message);
        }

        // Only a BUZZER-mode player may submit.
        if (gameSession.getPlayerModeById(message.getOriginatingPlayerId()) != PlayerMode.BUZZER) {
            return ProcessError.builder()
                    .recipient(message.getOriginatingPlayerId())
                    .error("Player mode is not buzzer")
                    .build();
        }

        // Must be awaiting a buzz (equivalently: mid-read).
        RoundState state = gameSession.getCurrentRound().getRoundState();
        if (state != RoundState.AWAITING_BUZZ && state != RoundState.PROCTOR_READING) {
            return ProcessError.builder()
                    .recipient(message.getOriginatingPlayerId())
                    .error("Answer submitted when round is in unsupported state")
                    .build();
        }

        String teamId = gameSession.getTeamByPlayerId(message.getOriginatingPlayerId()).getTeamId();

        // Register the buzz (moves the round to AWAITING_ANSWER) and stop the timer.
        gameSession.getCurrentRound().processBuzz(message.getOriginatingPlayerId(), teamId);
        gameSession.getCurrentRound().clearTossupTimer();

        // Judge the guess against the tossup's answer line.
        JudgeResult verdict = answerJudgeService.judge(
                gameSession.getCurrentRound().getAnswer(), submitAnswer.getAnswerText());
        boolean correct = verdict.isAccept();

        if (correct) {
            gameSession.getCurrentRound().processCorrectAnswer();
        } else {
            gameSession.getCurrentRound().processIncorrectAnswer();
        }
        // One guess per tossup in single player — complete the round either way (bonuses
        // are disabled for this mode, so a correct answer has no bonus phase to enter).
        gameSession.getCurrentMatch().completeRound();

        return createSinglePlayerAnswerUpdate(gameSession, correct);
    }

    /**
     * Builds the answer broadcast for single-player mode. There is no proctor, so this
     * never dereferences {@code getProctor()}: the round is COMPLETED when this runs, so
     * the full round (with the revealed answer) goes to the lone player unfiltered.
     */
    /**
     * Auto-proctor answer: the buzzed-in player's typed answer is judged, then handled with
     * the same wrong/right flow as a proctored game — a wrong answer returns play to the other
     * teams, a right answer scores the tossup (bonuses are a later increment). Broadcast to all.
     */
    private SockbowlOutMessage autoProctorSubmit(GameSession gameSession, SubmitAnswer submitAnswer) {
        RoundState state = gameSession.getCurrentRound().getRoundState();
        if (state == RoundState.BONUS_AWAITING_ANSWER) {
            return autoProctorBonusAnswer(gameSession, submitAnswer);
        }

        String playerId = submitAnswer.getOriginatingPlayerId();
        Round round = gameSession.getCurrentRound();
        if (state != RoundState.AWAITING_ANSWER) {
            return ProcessError.builder().recipient(playerId)
                    .error("No active question to answer").build();
        }
        if (round.getCurrentBuzz() == null || !playerId.equals(round.getCurrentBuzz().getPlayerId())) {
            return ProcessError.builder().recipient(playerId)
                    .error("Only the buzzed-in player may answer").build();
        }

        String eligibleTeam = round.getCurrentBuzz().getTeamId();
        boolean correct = answerJudgeService.judge(round.getAnswer(), submitAnswer.getAnswerText()).isAccept();

        if (correct) {
            round.processCorrectAnswer();
            if (gameSession.getGameSettings().isBonusesEnabled() && round.hasAssociatedBonus()) {
                // Set up the bonus (currentBonus / bonusEligibleTeamId / part index 0) but pause
                // before it starts. The tossup result (revealed by the sanitizer for BONUS_PENDING)
                // is shown first; an explicit StartBonus message from the eligible team or the
                // owner advances to BONUS_AWAITING_ANSWER — see #startBonus below.
                gameSession.getCurrentMatch().startBonusPhase(eligibleTeam);
                round.setRoundState(RoundState.BONUS_PENDING);
            } else {
                gameSession.getCurrentMatch().completeRound();
            }
        } else {
            round.processIncorrectAnswer();
            // Complete only once every team has a wrong buzz; otherwise other teams may buzz.
            boolean allTeamsMissed = gameSession.getTeamList().stream().allMatch(team ->
                    round.getBuzzList().stream()
                            .filter(b -> b.getTeamId().equals(team.getTeamId()))
                            .anyMatch(b -> !b.isCorrect()));
            if (allTeamsMissed) {
                gameSession.getCurrentMatch().completeRound();
            } else if (round.getRoundState() == RoundState.AWAITING_BUZZ) {
                // Question was already fully read before this wrong buzz — the reveal-tick
                // loop won't re-arm the timer (it only arms on the reveal-complete transition),
                // so restart it here exactly as the human-proctor flow does in playerAnswer().
                int timerDuration = gameSession.getGameSettings().getTimerSettings().getTossupTimerSeconds();
                round.startTossupTimer(timerDuration);
            }
        }
        return createProctorlessAnswerUpdate(gameSession, correct);
    }

    /** Auto-judged bonus part answer from the eligible team; advances the bonus or completes the round. */
    private SockbowlOutMessage autoProctorBonusAnswer(GameSession gameSession, SubmitAnswer submitAnswer) {
        String playerId = submitAnswer.getOriginatingPlayerId();
        Round round = gameSession.getCurrentRound();

        Team team = gameSession.getTeamByPlayerId(playerId);
        if (team == null || !team.getTeamId().equals(round.getBonusEligibleTeamId())) {
            return ProcessError.builder().recipient(playerId)
                    .error("Only the team that won the tossup may answer the bonus").build();
        }

        int idx = round.getCurrentBonusPartIndex();
        boolean correct = answerJudgeService.judge(bonusPartAnswerAt(round, idx), submitAnswer.getAnswerText()).isAccept();
        round.processBonusPartAnswer(idx, correct);
        round.advanceToNextBonusPart();

        if (round.getRoundState() == RoundState.BONUS_COMPLETED) {
            gameSession.getCurrentMatch().completeRound();
        } else {
            autoBonusReady(round);
        }
        return createProctorlessAnswerUpdate(gameSession, correct);
    }

    /** Collapse the proctor-read bonus states straight to "awaiting answer" — there's no proctor. */
    private void autoBonusReady(Round round) {
        RoundState s = round.getRoundState();
        if (s == RoundState.BONUS_READING_PREAMBLE || s == RoundState.BONUS_READING_PART) {
            round.setRoundState(RoundState.BONUS_AWAITING_ANSWER);
        }
    }

    /** The answer of the bonus part at the given index (by order, falling back to list position). */
    private static String bonusPartAnswerAt(Round round, int idx) {
        if (round.getCurrentBonus() == null || round.getCurrentBonus().getBonusParts() == null) {
            return "";
        }
        java.util.List<com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart> parts =
                round.getCurrentBonus().getBonusParts();
        return parts.stream()
                .filter(p -> p.getOrder() != null && p.getOrder() == idx && p.getBonusPart() != null)
                .findFirst()
                .map(p -> p.getBonusPart().getAnswer())
                .orElseGet(() -> (idx < parts.size() && parts.get(idx).getBonusPart() != null)
                        ? parts.get(idx).getBonusPart().getAnswer() : "");
    }

    /** Broadcast an AnswerUpdate to all players — full round when completed (answer revealed), else answer hidden. */
    private SockbowlOutMessage createProctorlessAnswerUpdate(GameSession gameSession, boolean correct) {
        Round round = gameSession.getCurrentRound();
        Round view = round.getRoundState() == RoundState.COMPLETED
                ? round
                : GameSanitizer.revealQuestionHideAnswer(round, gameSession.getGameSettings().getGameMode());
        AnswerUpdate update = AnswerUpdate.builder()
                .currentRound(view)
                .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                .correct(correct)
                .build();
        return SockbowlMultiOutMessage.builder().sockbowlOutMessage(update).build();
    }

    private SockbowlOutMessage createSinglePlayerAnswerUpdate(GameSession gameSession, boolean isCorrect) {
        AnswerUpdate answerUpdate = AnswerUpdate.builder()
                .currentRound(gameSession.getCurrentRound())
                .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                .correct(isCorrect)
                .build(); // no recipient → broadcast to the session

        return SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(answerUpdate)
                .build();
    }

    public SockbowlOutMessage timeout(SockbowlInMessage timeoutMessage) {
        GameSession gameSession = timeoutMessage.getGameSession();

        boolean proctorless = gameSession.getGameSettings().isProctorless();

        // Authorize: proctorless (auto-proctor) games have no real proctor, so only the
        // game owner may force the buzz window closed; otherwise only the proctor may.
        if (proctorless) {
            Player originator = gameSession.getPlayerById(timeoutMessage.getOriginatingPlayerId());
            if (originator == null || !originator.isGameOwner()) {
                return ProcessError.accessDeniedMessage(timeoutMessage);
            }
        } else if (gameSession.getPlayerModeById(timeoutMessage.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
            return ProcessError.accessDeniedMessage(timeoutMessage);
        }

        // Check if the current round state allows a timeout. Auto-proctor games never
        // receive a FinishedReading message, so the round may still be sitting in
        // PROCTOR_READING when nobody buzzes; accept that state too when proctorless.
        RoundState currentRoundState = gameSession.getCurrentRound().getRoundState();
        boolean validState = proctorless
                ? (currentRoundState == RoundState.PROCTOR_READING || currentRoundState == RoundState.AWAITING_BUZZ)
                : currentRoundState == RoundState.AWAITING_BUZZ;
        if (!validState) {
            return ProcessError.builder()
                    .recipient(timeoutMessage.getOriginatingPlayerId())
                    .error("Timeout message processed when round is not awaiting buzz")
                    .build();
        }

        // Clear tossup timer
        gameSession.getCurrentRound().clearTossupTimer();

        // Set the round state to completed
        gameSession.getCurrentMatch().completeRound();

        // Proctorless completions always reveal the full round to everyone (there's no
        // human proctor to keep secrets from) — match the broadcast pattern used by
        // autoProctorSubmit/autoProctorBonusAnswer. Nobody buzzed, so there's no correct answer.
        if (proctorless) {
            return createProctorlessAnswerUpdate(gameSession, false);
        }

        return createRoundUpdateMessages(gameSession);
    }


    /**
     * Handles the 'Finished Reading' event in a game session.
     * It's invoked when a proctor finishes reading a question and checks the current round state.
     * If the state is valid, it updates the round state and sends update messages.
     * Otherwise, returns a ProcessError message.
     *
     * @param finishedReadingMessage The incoming message indicating the proctor has finished reading.
     * @return SockbowlOutMessage containing round updates or a ProcessError message.
     * @throws NullPointerException if the finishedReadingMessage or its nested objects are null.
     */
    public SockbowlOutMessage finishedReading(SockbowlInMessage finishedReadingMessage) {
        GameSession gameSession = finishedReadingMessage.getGameSession();

        // Single player has no proctor reading aloud.
        if (gameSession.getGameSettings().getGameMode() == GameMode.SINGLE_PLAYER) {
            return ProcessError.accessDeniedMessage(finishedReadingMessage);
        }

        // Check if the current round state is 'PROCTOR_READING' or 'AWAITING_BUZZ'
        RoundState currentRoundState = gameSession.getCurrentRound().getRoundState();

        // If already in AWAITING_BUZZ, this is an idempotent retry - just return success
        if (currentRoundState == RoundState.AWAITING_BUZZ) {
            return createRoundUpdateMessages(gameSession);
        }

        // Only allow transition from PROCTOR_READING state
        if (currentRoundState != RoundState.PROCTOR_READING) {
            return ProcessError.builder()
                    .recipient(finishedReadingMessage.getOriginatingPlayerId())
                    .error("Finished reading message processed in an unsupported round state")
                    .build();
        }

        // Set the round state to 'AWAITING_BUZZ' and update the reading state
        gameSession.getCurrentRound().setRoundState(RoundState.AWAITING_BUZZ);
        gameSession.getCurrentRound().setProctorFinishedReading(true);

        // Start tossup timer
        int timerDuration = gameSession.getGameSettings().getTimerSettings().getTossupTimerSeconds();
        gameSession.getCurrentRound().startTossupTimer(timerDuration);

        return createRoundUpdateMessages(gameSession);
    }


    /**
     * Processes the request to advance to the next round in the game.
     * This method is only callable if the current round status is 'COMPLETED' and the originating player is the proctor.
     * It advances the round and sends a round update to all users, with non-proctors receiving a sanitized round.
     *
     * @param sockbowlInMessage The incoming message requesting to advance the round.
     * @return SockbowlOutMessage containing round updates.
     * @throws NullPointerException     if the sockbowlInMessage or its nested objects are null.
     * @throws IllegalArgumentException if the round state is not 'COMPLETED' or if the originating player is not the proctor.
     */
    public SockbowlOutMessage advanceRound(SockbowlInMessage sockbowlInMessage) {
        GameSession gameSession = sockbowlInMessage.getGameSession();

        boolean proctorless = gameSession.getGameSettings().isProctorless();

        // Authorize: single player has no proctor, so the game owner advances; otherwise
        // only the proctor may.
        if (proctorless) {
            Player advancer = gameSession.getPlayerById(sockbowlInMessage.getOriginatingPlayerId());
            if (advancer == null || !advancer.isGameOwner()) {
                return ProcessError.accessDeniedMessage(sockbowlInMessage);
            }
        } else if (gameSession.getPlayerModeById(sockbowlInMessage.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
            return ProcessError.accessDeniedMessage(sockbowlInMessage);
        }

        // Check if the current round status is 'COMPLETED'
        if (gameSession.getCurrentRound().getRoundState() != RoundState.COMPLETED) {
            return ProcessError.builder()
                    .recipient(sockbowlInMessage.getOriginatingPlayerId())
                    .error("Cannot advance round when current round is not completed")
                    .build();
        }

        // Advance to the next round
        gameSession.getCurrentMatch().advanceRound();

        if(gameSession.getCurrentMatch().getMatchState() == MatchState.COMPLETED){
            // Sanitize and return the session
            GameSession gameSessionSanitized = GameSanitizer.sanitizeGameSession(gameSession, PlayerMode.PROCTOR);
            return GameSessionUpdate.builder()
                    .gameSession(gameSessionSanitized)
                    .build();
        }

        // Single player: broadcast the next round with the question visible, answer hidden.
        if (proctorless) {
            RoundUpdate roundUpdate = RoundUpdate.builder()
                    .round(GameSanitizer.revealQuestionHideAnswer(gameSession.getCurrentRound(), gameSession.getGameSettings().getGameMode()))
                    .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                    .build();
            return SockbowlMultiOutMessage.builder()
                    .sockbowlOutMessage(roundUpdate)
                    .build();
        }

        return createRoundUpdateMessages(gameSession);
    }

    /**
     * Starts the bonus phase from BONUS_PENDING, entering BONUS_AWAITING_ANSWER at part 0
     * exactly where startBonusPhase()+autoBonusReady() used to leave the round before this
     * pause existed. Callable by any player on the bonus-eligible team, or the game owner
     * (mirrors the owner-may-act-for-the-room pattern used by timeout()/advanceRound()).
     * Idempotent if the bonus has already started; rejected otherwise.
     *
     * @param message the incoming {@link StartBonus}
     * @return an AnswerUpdate broadcast, or a ProcessError
     */
    public SockbowlOutMessage startBonus(SockbowlInMessage message) {
        GameSession gameSession = message.getGameSession();
        Round round = gameSession.getCurrentRound();
        String originatingPlayerId = message.getOriginatingPlayerId();

        Player originator = gameSession.getPlayerById(originatingPlayerId);
        Team eligibleTeam = gameSession.getTeamList().stream()
                .filter(t -> t.getTeamId().equals(round.getBonusEligibleTeamId()))
                .findFirst()
                .orElse(null);
        boolean isEligiblePlayer = eligibleTeam != null && eligibleTeam.isPlayerOnTeam(originatingPlayerId);
        boolean isOwner = originator != null && originator.isGameOwner();

        if (!isEligiblePlayer && !isOwner) {
            return ProcessError.accessDeniedMessage(message);
        }

        RoundState state = round.getRoundState();

        // Idempotent retry: bonus already started, nothing to do.
        if (state == RoundState.BONUS_AWAITING_ANSWER) {
            return createProctorlessAnswerUpdate(gameSession, true);
        }

        if (state != RoundState.BONUS_PENDING) {
            return ProcessError.builder()
                    .recipient(originatingPlayerId)
                    .error("Start bonus message processed when round is not pending a bonus start")
                    .build();
        }

        // Enter the bonus exactly where autoBonusReady would have left it: part 0, awaiting answer.
        round.setRoundState(RoundState.BONUS_AWAITING_ANSWER);

        return createProctorlessAnswerUpdate(gameSession, true);
    }


    /**
     * Creates round update messages for the game session.
     * If the current round's state is 'COMPLETED', it creates a full context update without specific recipients,
     * which will be sent to all players. Otherwise, it creates both full context (for the proctor) and limited context updates.
     *
     * @param gameSession The current game session containing all the necessary game data.
     * @return SockbowlOutMessage containing round updates.
     */
    private SockbowlOutMessage createRoundUpdateMessages(GameSession gameSession) {
        RoundUpdate fullContextUpdate;

        if (gameSession.getCurrentRound().getRoundState() == RoundState.COMPLETED) {
            // If round is completed, create full context update without specific recipients
            fullContextUpdate = RoundUpdate.builder()
                    .round(gameSession.getCurrentRound())
                    .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                    .build(); // No recipient is set, so it will be sent to all players

            return SockbowlMultiOutMessage.builder()
                    .sockbowlOutMessage(fullContextUpdate)
                    .build();
        }

        // If round is not completed, create full context update for proctor and limited context update for other players
        fullContextUpdate = RoundUpdate.builder()
                .round(gameSession.getCurrentRound())
                .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                .recipient(gameSession.getProctor().getPlayerId())
                .build();

        RoundUpdate limitedContextUpdate = RoundUpdate.builder()
                .round(sanitizeRound(gameSession.getCurrentRound()))
                .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                .recipients(gameSession.getPlayerList().stream()
                        .map(Player::getPlayerId)
                        .filter(playerId -> !playerId.equals(gameSession.getProctor().getPlayerId()))
                        .toList())
                .build();

        return SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(fullContextUpdate)
                .sockbowlOutMessage(limitedContextUpdate)
                .build();
    }

    /**
     * Creates a SockbowlOutMessage containing either a single or both full and limited context AnswerUpdate messages.
     * A single message is created when the round is completed; otherwise, separate messages for the proctor
     * (full context) and other players (limited context) are created.
     *
     * @param gameSession The current game session containing all the necessary game data.
     * @param isCorrect   Flag to indicate whether the answer was correct or not.
     * @return SockbowlOutMessage containing the appropriate messages based on the round state.
     */
    private SockbowlOutMessage createAnswerUpdateMessages(GameSession gameSession, boolean isCorrect) {
        AnswerUpdate fullContextMessage = AnswerUpdate.builder()
                .currentRound(gameSession.getCurrentRound())
                .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                .correct(isCorrect)
                .playerId(gameSession.getProctor().getPlayerId())
                .build();

        // Check if the round is completed
        if (gameSession.getCurrentRound().getRoundState() == RoundState.COMPLETED) {
            // If round is completed, return only the full context update
            return SockbowlMultiOutMessage.builder()
                    .sockbowlOutMessage(fullContextMessage)
                    .build();
        }

        // Create limited context message for other players
        Round roundForLimitedContext = sanitizeRound(gameSession.getCurrentRound());
        List<String> nonProctorPlayerIds = gameSession.getPlayerList().stream()
                .map(Player::getPlayerId)
                .filter(playerId -> !playerId.equals(gameSession.getProctor().getPlayerId()))
                .toList();

        AnswerUpdate limitedContextMessage = AnswerUpdate.builder()
                .currentRound(roundForLimitedContext)
                .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                .correct(isCorrect)
                .recipients(nonProctorPlayerIds)
                .build();

        // Return both full and limited context messages
        return SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(fullContextMessage)
                .sockbowlOutMessage(limitedContextMessage)
                .build();
    }

    /**
     * Processes bonus part answers from the proctor.
     * Validates that the sender is the proctor and that the round is in the correct state.
     * Updates the bonus part answer and broadcasts the update to all players.
     *
     * @param bonusPartOutcomeMsg The incoming bonus part outcome message
     * @return BonusUpdate message or ProcessError
     */
    public SockbowlOutMessage bonusPartAnswer(SockbowlInMessage bonusPartOutcomeMsg) {
        BonusPartOutcome bonusPartOutcome = (BonusPartOutcome) bonusPartOutcomeMsg;
        GameSession gameSession = bonusPartOutcomeMsg.getGameSession();

        // Check if the player is the proctor
        if (gameSession.getPlayerModeById(bonusPartOutcomeMsg.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
            return ProcessError.accessDeniedMessage(bonusPartOutcomeMsg);
        }

        // Check if we're in the correct state
        if (gameSession.getCurrentRound().getRoundState() != RoundState.BONUS_AWAITING_ANSWER) {
            return ProcessError.builder()
                    .recipient(bonusPartOutcomeMsg.getOriginatingPlayerId())
                    .error("Bonus part answer processed when round is not awaiting bonus answer")
                    .build();
        }

        // Validate part index
        if (bonusPartOutcome.getPartIndex() < 0 || bonusPartOutcome.getPartIndex() > 2) {
            return ProcessError.builder()
                    .recipient(bonusPartOutcomeMsg.getOriginatingPlayerId())
                    .error("Invalid bonus part index: " + bonusPartOutcome.getPartIndex())
                    .build();
        }

        // Process the bonus part answer
        gameSession.getCurrentRound().processBonusPartAnswer(
                bonusPartOutcome.getPartIndex(),
                bonusPartOutcome.isCorrect()
        );

        // Clear bonus timer
        gameSession.getCurrentRound().clearBonusTimer();

        // Advance to next part
        gameSession.getCurrentRound().advanceToNextBonusPart();

        // If all parts answered, complete the bonus phase and the round
        if (gameSession.getCurrentRound().getRoundState() == RoundState.BONUS_COMPLETED) {
            gameSession.getCurrentMatch().completeBonusPhase();
        }

        // Create and return bonus update message
        return createBonusUpdateMessages(gameSession, bonusPartOutcome.getPartIndex(), bonusPartOutcome.isCorrect());
    }

    /**
     * Handles the 'Finished Reading Bonus Preamble' event.
     * Transitions from BONUS_READING_PREAMBLE to BONUS_READING_PART for the first part.
     *
     * @param message The incoming message
     * @return SockbowlOutMessage containing round updates or error
     */
    public SockbowlOutMessage finishedReadingBonusPreamble(SockbowlInMessage message) {
        GameSession gameSession = message.getGameSession();

        // Check if the player is the proctor
        if (gameSession.getPlayerModeById(message.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
            return ProcessError.accessDeniedMessage(message);
        }

        RoundState currentRoundState = gameSession.getCurrentRound().getRoundState();

        // If already in BONUS_READING_PART, this is an idempotent retry - just return success
        if (currentRoundState == RoundState.BONUS_READING_PART) {
            return createRoundUpdateMessages(gameSession);
        }

        // Only allow transition from BONUS_READING_PREAMBLE state
        if (currentRoundState != RoundState.BONUS_READING_PREAMBLE) {
            return ProcessError.builder()
                    .recipient(message.getOriginatingPlayerId())
                    .error("Finished reading bonus preamble message processed in unsupported state")
                    .build();
        }

        // Transition to reading first part
        gameSession.getCurrentRound().setProctorFinishedReadingBonusPreamble(true);
        gameSession.getCurrentRound().setRoundState(RoundState.BONUS_READING_PART);

        return createRoundUpdateMessages(gameSession);
    }

    /**
     * Handles the 'Finished Reading Bonus Part' event.
     * Transitions from BONUS_READING_PART to BONUS_AWAITING_ANSWER and starts the timer.
     *
     * @param message The incoming message
     * @return SockbowlOutMessage containing round updates or error
     */
    public SockbowlOutMessage finishedReadingBonusPart(SockbowlInMessage message) {
        GameSession gameSession = message.getGameSession();

        // Check if the player is the proctor
        if (gameSession.getPlayerModeById(message.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
            return ProcessError.accessDeniedMessage(message);
        }

        RoundState currentRoundState = gameSession.getCurrentRound().getRoundState();

        // If already in BONUS_AWAITING_ANSWER, this is an idempotent retry - just return success
        if (currentRoundState == RoundState.BONUS_AWAITING_ANSWER) {
            return createRoundUpdateMessages(gameSession);
        }

        // Only allow transition from BONUS_READING_PART state
        if (currentRoundState != RoundState.BONUS_READING_PART) {
            return ProcessError.builder()
                    .recipient(message.getOriginatingPlayerId())
                    .error("Finished reading bonus part message processed in unsupported state")
                    .build();
        }

        // Start the timer for this part
        gameSession.getCurrentRound().startBonusPartTimer();

        // Start bonus timer with configured duration
        int timerDuration = gameSession.getGameSettings().getTimerSettings().getBonusTimerSeconds();
        gameSession.getCurrentRound().startBonusTimer(timerDuration);

        return createRoundUpdateMessages(gameSession);
    }

    /**
     * Handles timeout for a bonus part.
     * Auto-marks the current part as incorrect and advances to the next part.
     *
     * @param message The incoming timeout message
     * @return SockbowlOutMessage containing bonus update or error
     */
    public SockbowlOutMessage timeoutBonusPart(SockbowlInMessage message) {
        GameSession gameSession = message.getGameSession();

        // Check if the player is the proctor
        if (gameSession.getPlayerModeById(message.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
            return ProcessError.accessDeniedMessage(message);
        }

        // Check if the current round state is 'BONUS_AWAITING_ANSWER'
        if (gameSession.getCurrentRound().getRoundState() != RoundState.BONUS_AWAITING_ANSWER) {
            return ProcessError.builder()
                    .recipient(message.getOriginatingPlayerId())
                    .error("Timeout bonus part message processed when not awaiting answer")
                    .build();
        }

        int currentPartIndex = gameSession.getCurrentRound().getCurrentBonusPartIndex();

        // Clear bonus timer
        gameSession.getCurrentRound().clearBonusTimer();

        // Handle timeout (auto-mark incorrect and advance)
        gameSession.getCurrentRound().timeoutBonusPart();

        // If all parts complete, complete the bonus phase
        if (gameSession.getCurrentRound().getRoundState() == RoundState.BONUS_COMPLETED) {
            gameSession.getCurrentMatch().completeBonusPhase();
        }

        // Return bonus update message
        return createBonusUpdateMessages(gameSession, currentPartIndex, false);
    }

    /**
     * Creates bonus update messages - full context for everyone since bonus answers are public
     *
     * @param gameSession The game session
     * @param partIndex Which part was just judged
     * @param correct Whether it was correct
     * @return BonusUpdate message
     */
    private SockbowlOutMessage createBonusUpdateMessages(GameSession gameSession, int partIndex, boolean correct) {
        BonusUpdate bonusUpdate = BonusUpdate.builder()
                .currentRound(gameSession.getCurrentRound())
                .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                .partIndex(partIndex)
                .correct(correct)
                .build();

        return SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(bonusUpdate)
                .build();
    }


}
