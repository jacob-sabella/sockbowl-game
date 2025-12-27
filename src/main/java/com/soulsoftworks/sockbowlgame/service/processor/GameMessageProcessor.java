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
    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(PlayerIncomingBuzz.class, this::playerBuzz);
        processorMapping.registerProcessor(AnswerOutcome.class, this::playerAnswer);
        processorMapping.registerProcessor(BonusPartOutcome.class, this::bonusPartAnswer);
        processorMapping.registerProcessor(TimeoutRound.class, this::timeout);
        processorMapping.registerProcessor(FinishedReading.class, this::finishedReading);
        processorMapping.registerProcessor(AdvanceRound.class, this::advanceRound);
        processorMapping.registerProcessor(FinishedReadingBonusPreamble.class, this::finishedReadingBonusPreamble);
        processorMapping.registerProcessor(FinishedReadingBonusPart.class, this::finishedReadingBonusPart);
        processorMapping.registerProcessor(TimeoutBonusPart.class, this::timeoutBonusPart);
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

        // Get the teamId of the player who buzzed
        String teamId = gameSession.getTeamByPlayerId(playerBuzz.getOriginatingPlayerId()).getTeamId();

        // Check if the team has already buzzed this round, if so return an error message
        if (gameSession.getCurrentRound().hasTeamBuzzed(teamId)) {
            return ProcessError.builder()
                    .recipient(playerBuzz.getOriginatingPlayerId())
                    .error("Team has already buzzed in this round")
                    .build();
        }

        // Check if the player is in the BUZZER mode, if not return an error message
        if (gameSession.getPlayerById(playerBuzz.getOriginatingPlayerId()).getPlayerMode() != PlayerMode.BUZZER) {
            return ProcessError.builder()
                    .recipient(playerBuzz.getOriginatingPlayerId())
                    .error("Player mode is not buzzer")
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
    public SockbowlOutMessage timeout(SockbowlInMessage timeoutMessage) {
        GameSession gameSession = timeoutMessage.getGameSession();

        // Check if the player is the proctor, if not return an error message
        if (gameSession.getPlayerModeById(timeoutMessage.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
            return ProcessError.accessDeniedMessage(timeoutMessage);
        }

        // Check if the current round state is 'AWAITING_BUZZ'
        if (gameSession.getCurrentRound().getRoundState() != RoundState.AWAITING_BUZZ) {
            return ProcessError.builder()
                    .recipient(timeoutMessage.getOriginatingPlayerId())
                    .error("Timeout message processed when round is not awaiting buzz")
                    .build();
        }

        // Set the round state to completed
        gameSession.getCurrentMatch().completeRound();

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

        // Check if the current round state is 'PROCTOR_READING' or 'AWAITING_BUZZ'
        RoundState currentRoundState = gameSession.getCurrentRound().getRoundState();
        if (currentRoundState != RoundState.PROCTOR_READING) {
            return ProcessError.builder()
                    .recipient(finishedReadingMessage.getOriginatingPlayerId())
                    .error("Finished reading message processed in an unsupported round state")
                    .build();
        }

        // Set the round state to 'AWAITING_BUZZ' and update the reading state
        gameSession.getCurrentRound().setRoundState(RoundState.AWAITING_BUZZ);
        gameSession.getCurrentRound().setProctorFinishedReading(true);

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

        // Check if the originating player is the proctor
        if (gameSession.getPlayerModeById(sockbowlInMessage.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
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

        return createRoundUpdateMessages(gameSession);
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

        // Check if the current round state is 'BONUS_READING_PREAMBLE'
        if (gameSession.getCurrentRound().getRoundState() != RoundState.BONUS_READING_PREAMBLE) {
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

        // Check if the current round state is 'BONUS_READING_PART'
        if (gameSession.getCurrentRound().getRoundState() != RoundState.BONUS_READING_PART) {
            return ProcessError.builder()
                    .recipient(message.getOriginatingPlayerId())
                    .error("Finished reading bonus part message processed in unsupported state")
                    .build();
        }

        // Start the timer for this part
        gameSession.getCurrentRound().startBonusPartTimer();

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
