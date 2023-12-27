package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.*;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.CorrectAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.IncorrectAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.PlayerBuzzed;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.RoundUpdate;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.model.state.PlayerMode;
import com.soulsoftworks.sockbowlgame.model.state.RoundState;
import org.springframework.stereotype.Service;

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
        processorMapping.registerProcessor(AnswerIncorrect.class, this::playerAnswer);
        processorMapping.registerProcessor(AnswerCorrect.class, this::playerAnswer);
        processorMapping.registerProcessor(TimeoutRound.class, this::timeout);
        processorMapping.registerProcessor(FinishedReading.class, this::finishedReading);
    }


    /**
     * Method to process the player's buzz action in the game.
     * It checks for the player's mode and the current game state before processing the buzz.
     *
     * @param playerBuzz the incoming message from the player containing the buzz action.
     * @return SockbowlOutMessage indicating the result of the buzz processing. It can be null if there are no errors.
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
                .build();

        PlayerBuzzed limitedContextPlayerBuzzed = PlayerBuzzed.builder()
                .playerId(playerBuzz.getOriginatingPlayerId())
                .teamId(gameSession.getTeamByPlayerId(playerBuzz.getOriginatingPlayerId()).getTeamId())
                .round(sanitizeRound(gameSession.getCurrentRound()))
                .build();

        // Return player buzz message to all players
        return SockbowlMultiOutMessage
                .builder()
                .sockbowlOutMessage(fullContextPlayerBuzzed)
                .sockbowlOutMessage(limitedContextPlayerBuzzed)
                .build();
    }

    /**
     * Processes a player's answer type based on the current game session and the type of the provided message.
     * <p>
     * This method checks if:
     * <ul>
     *     <li>The originating player is the proctor.</li>
     *     <li>The current game round is in a state where an answer can be processed.</li>
     *     <li>The type of the answer provided (either correct or incorrect).</li>
     * </ul>
     * If the checks pass, the method processes the answer accordingly and returns either a CorrectAnswer or IncorrectAnswer message.
     * Otherwise, it returns a ProcessError message.
     * </p>
     *
     * @param answer The incoming message which contains details about the player's answer, originating player ID, and the game session.
     * @return SockbowlOutMessage Represents the outcome of the processed answer, which can be of type CorrectAnswer, IncorrectAnswer, or ProcessError.
     * @throws NullPointerException if the answer or its nested objects are null.
     * @throws IllegalArgumentException if the state of the game session or the provided answer does not meet the expected conditions.
     */
    public SockbowlOutMessage playerAnswer(SockbowlInMessage answer) {
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

        // Call into the round the process an incorrect answer to get the round into the correct state
        if(answer instanceof AnswerIncorrect){
            gameSession.getCurrentRound().processIncorrectAnswer();
            return IncorrectAnswer.builder().currentRound(gameSession.getCurrentRound()).build();
        } else{
            gameSession.getCurrentRound().processCorrectAnswer();
            gameSession.getCurrentMatch().advanceRound();
            return CorrectAnswer.builder().currentRound(gameSession.getCurrentRound()).build();
        }
    }

    /**
     * Processes a timeout message in a game session.
     * This method is called when a timeout occurs during the game.
     * It checks the current round state and processes the timeout if the state is 'AWAITING_BUZZ'.
     * On successful processing, it sets the round state to 'COMPLETED' and advances to the next round.
     * It then creates and sends round update messages to the players.
     *
     * @param timeoutMessage The incoming timeout message containing the game session information.
     * @return SockbowlOutMessage Either a SockbowlMultiOutMessage containing round updates or a ProcessError message.
     * @throws NullPointerException if the timeoutMessage or its nested objects are null.
     */
    public SockbowlOutMessage timeout(SockbowlInMessage timeoutMessage) {
        GameSession gameSession = timeoutMessage.getGameSession();

        // Check if the current round state is 'AWAITING_BUZZ'
        if (gameSession.getCurrentRound().getRoundState() != RoundState.AWAITING_BUZZ) {
            return ProcessError.builder()
                    .recipient(timeoutMessage.getOriginatingPlayerId())
                    .error("Timeout message processed when round is not awaiting buzz")
                    .build();
        }

        // Set the round state to Buzz and increment the round
        gameSession.getCurrentRound().setRoundState(RoundState.COMPLETED);
        gameSession.getCurrentMatch().advanceRound();

        // Create round update messages
        RoundUpdate fullContextUpdate = RoundUpdate
                .builder()
                .round(gameSession.getCurrentRound())
                .recipient(gameSession.getProctor().getPlayerId())
                .build();

        RoundUpdate limitedContextUpdate = RoundUpdate.builder()
                .round(sanitizeRound(gameSession.getCurrentRound()))
                .recipients(gameSession.getPlayerList().stream()
                        .map(Player::getPlayerId)
                        .filter(playerId -> !playerId.equals(gameSession.getProctor().getPlayerId()))
                        .collect(Collectors.toList()))
                .build();

        // Send multi-message back to processor
        return SockbowlMultiOutMessage
                .builder()
                .sockbowlOutMessage(fullContextUpdate)
                .sockbowlOutMessage(limitedContextUpdate)
                .build();
    }


    /**
     * Handles the 'Finished Reading' message in a game session.
     * This method is invoked when a proctor finishes reading a question.
     * It checks if the current round state is either 'PROCTOR_READING' or 'AWAITING_BUZZ'
     * and sets the round state to 'AWAITING_ANSWER' if the condition is met.
     * If the round is not in the correct state, a ProcessError is returned.
     * Round update messages are created and sent to all players, indicating the new state of the round.
     *
     * @param finishedReadingMessage The incoming message indicating that the proctor has finished reading.
     * @return SockbowlOutMessage Either a SockbowlMultiOutMessage containing round updates or a ProcessError message.
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

        // Set the round state to 'AWAITING_ANSWER'
        gameSession.getCurrentRound().setRoundState(RoundState.AWAITING_BUZZ);

        // Create round update messages
        RoundUpdate fullContextUpdate = RoundUpdate
                .builder()
                .round(gameSession.getCurrentRound())
                .recipient(gameSession.getProctor().getPlayerId())
                .build();

        RoundUpdate limitedContextUpdate = RoundUpdate.builder()
                .round(sanitizeRound(gameSession.getCurrentRound()))
                .recipients(gameSession.getPlayerList().stream()
                        .map(Player::getPlayerId)
                        .filter(playerId -> !playerId.equals(gameSession.getProctor().getPlayerId()))
                        .collect(Collectors.toList()))
                .build();

        // Send multi-message back to processor
        return SockbowlMultiOutMessage
                .builder()
                .sockbowlOutMessage(fullContextUpdate)
                .sockbowlOutMessage(limitedContextUpdate)
                .build();
    }


}
