package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.*;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.AdvanceRound;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.AnswerUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.PlayerBuzzed;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.RoundUpdate;
import com.soulsoftworks.sockbowlgame.model.state.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

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
        processorMapping.registerProcessor(TimeoutRound.class, this::timeout);
        processorMapping.registerProcessor(FinishedReading.class, this::finishedReading);
        processorMapping.registerProcessor(AdvanceRound.class, this::advanceRound);
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
                        .collect(Collectors.toList()))
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
            return ProcessError.builder()
                    .recipient(answer.getOriginatingPlayerId())
                    .error("Originating player is not the proctor")
                    .build();
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
            gameSession.getCurrentRound().processCorrectAnswer();
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
            return ProcessError.builder()
                    .recipient(timeoutMessage.getOriginatingPlayerId())
                    .error("Originating player is not the proctor")
                    .build();
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

        // Set the round state to 'AWAITING_BUZZ'
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
            return ProcessError.builder()
                    .recipient(sockbowlInMessage.getOriginatingPlayerId())
                    .error("Originating player is not the proctor")
                    .build();
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
                        .collect(Collectors.toList()))
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


}
