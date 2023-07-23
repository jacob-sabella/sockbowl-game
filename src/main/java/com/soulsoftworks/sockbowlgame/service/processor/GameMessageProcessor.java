package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.PlayerIncomingBuzz;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.PlayerBuzzed;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.PlayerMode;
import com.soulsoftworks.sockbowlgame.model.state.RoundState;
import org.springframework.stereotype.Service;

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
    }

    /**
     * Method to process the player's buzz action in the game.
     * It checks for the player's mode and the current game state before processing the buzz.
     *
     * @param playerBuzz the incoming message from the player containing the buzz action.
     * @return SockbowlOutMessage indicating the result of the buzz processing. It can be null if there are no errors.
     */
    public SockbowlOutMessage playerBuzz(SockbowlInMessage playerBuzz){
        // Retrieve the current game session from the player's buzz
        GameSession gameSession = playerBuzz.getGameSession();

        // Check if the player is in the BUZZER mode, if not return an error message
        if(gameSession.getPlayerById(playerBuzz.getOriginatingPlayerId()).getPlayerMode() != PlayerMode.BUZZER){
            return ProcessError.builder()
                    .recipient(playerBuzz.getOriginatingPlayerId())
                    .error("Player mode is not buzzer")
                    .build();
        }

        // Check if the current game round is in a state that allows buzzing, if not return an error message
        if(gameSession.getCurrentRound().getRoundState() != RoundState.PROCTOR_READING &&
                gameSession.getCurrentRound().getRoundState() != RoundState.AWAITING_BUZZ){
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

        // Return player buzz message to all players
        return PlayerBuzzed.builder()
                .playerId(playerBuzz.getOriginatingPlayerId())
                .teamId(gameSession.getTeamByPlayerId(playerBuzz.getOriginatingPlayerId()).getTeamId())
                .build();
    }


    public SockbowlOutMessage playerAnswerIncorrect(SockbowlInMessage answerIncorrect){
        // Retrieve the current game session from the player's buzz
        GameSession gameSession = answerIncorrect.getGameSession();

        // Check if the player is the proctor, if not return an error message
        if(gameSession.getPlayerModeById(answerIncorrect.getOriginatingPlayerId()) != PlayerMode.PROCTOR){
            return ProcessError.builder()
                    .recipient(answerIncorrect.getOriginatingPlayerId())
                    .error("Originating player is not the proctor")
                    .build();
        }

        // We can only process this type of message if we're waiting for a buzz
        if(gameSession.getCurrentRound().getRoundState() != RoundState.AWAITING_BUZZ){
            return ProcessError.builder()
                    .recipient(answerIncorrect.getOriginatingPlayerId())
                    .error("Answer incorrect message processed when round is in unsupported state")
                    .build();
        }




    }
}
