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
        processorMapping.registerProcessor(AnswerIncorrect.class, this::playerAnswer);
        processorMapping.registerProcessor(AnswerCorrect.class, this::playerAnswer);
        processorMapping.registerProcessor(TimeoutRound.class, this::timeout);
        processorMapping.registerProcessor(FinishedReading.class, this::finishedReading);
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
     * @return SockbowlOutMessage representing the outcome (CorrectAnswer, IncorrectAnswer, or ProcessError).
     * @throws NullPointerException     if the answer or its nested objects are null.
     * @throws IllegalArgumentException if the game session state or the answer type is invalid.
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

        SockbowlOutMessage fullContextMessage;
        SockbowlOutMessage limitedContextMessage;

        // Create a list of recipients for the limited context message (all players except the proctor)
        List<String> nonProctorPlayerIds = gameSession.getPlayerList().stream()
                .map(Player::getPlayerId)
                .filter(playerId -> !playerId.equals(gameSession.getProctor().getPlayerId()))
                .toList();

        // Depending on the answer type, create different messages with sanitized round data
        if (answer instanceof AnswerIncorrect) {
            gameSession.getCurrentRound().processIncorrectAnswer();

            boolean shouldAdvanceRound = gameSession.getTeamList().stream()
                    .allMatch(team -> gameSession.getCurrentRound().getBuzzList().stream()
                            .filter(buzz -> buzz.getTeamId().equals(team.getTeamId()))
                            .anyMatch(buzz -> !buzz.isCorrect()));

            if (shouldAdvanceRound) {
                gameSession.getCurrentMatch().advanceRound();
            }

            fullContextMessage = IncorrectAnswer.builder()
                    .currentRound(gameSession.getCurrentRound())
                    .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                    .recipient(gameSession.getProctor().getPlayerId())
                    .build();

            limitedContextMessage = IncorrectAnswer.builder()
                    .currentRound(sanitizeRound(gameSession.getCurrentRound()))
                    .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                    .recipients(nonProctorPlayerIds)
                    .build();
        } else {

            gameSession.getCurrentRound().processCorrectAnswer();
            gameSession.getCurrentMatch().advanceRound();

            fullContextMessage = CorrectAnswer.builder()
                    .currentRound(gameSession.getCurrentRound())
                    .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                    .recipient(gameSession.getProctor().getPlayerId())
                    .build();

            limitedContextMessage = CorrectAnswer.builder()
                    .currentRound(sanitizeRound(gameSession.getCurrentRound()))
                    .previousRounds(gameSession.getCurrentMatch().getPreviousRounds())
                    .recipients(nonProctorPlayerIds)
                    .build();
        }


        // Return multi-message with both full and limited context messages
        return SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(fullContextMessage)
                .sockbowlOutMessage(limitedContextMessage)
                .build();
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

        // Set the round state to 'AWAITING_ANSWER'
        gameSession.getCurrentRound().setRoundState(RoundState.AWAITING_BUZZ);

        // Create round update messages
        RoundUpdate fullContextUpdate = RoundUpdate
                .builder()
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

        // Send multi-message back to processor
        return SockbowlMultiOutMessage
                .builder()
                .sockbowlOutMessage(fullContextUpdate)
                .sockbowlOutMessage(limitedContextUpdate)
                .build();
    }


}
