package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.RoundUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameStartedMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.MatchState;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.model.state.Team;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

import static com.soulsoftworks.sockbowlgame.model.state.GameSanitizer.sanitizeRound;

@Service
public class ProgressionMessageProcessor extends MessageProcessor {

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(StartMatch.class, this::startMatch);
    }

    public SockbowlOutMessage startMatch(SockbowlInMessage startMatchMessage){

        GameSession gameSession = startMatchMessage.getGameSession();

        // Check if the player making the request is the game owner
        if (!gameSession.isPlayerGameOwner(startMatchMessage.getOriginatingPlayerId())) {
            // If not, return access denied error message
            return ProcessError.accessDeniedMessage(startMatchMessage);
        }

        // Verify that a packet has been selected
        if (gameSession.getCurrentMatch().getPacket().getId() == 0) {
            // If packet ID is 90, return error message
            return ProcessError.builder().error("A packet must be selected for the match.").build();
        }

        // Verify that the match has a proctor
        if (gameSession.getProctor() == null) {
            // If no proctor, return error message
            return ProcessError.builder().error("No proctor assigned to the match.").build();
        }

        // Verify that each team has at least one player
        for (Team team : gameSession.getTeamList()) {
            if (team.getTeamPlayers() == null || team.getTeamPlayers().isEmpty()) {
                // If a team has no players, return error message
                return ProcessError.builder().error("Each team must have at least one player.").build();
            }
        }

        // Change the match state to in game
        gameSession.getCurrentMatch().setMatchState(MatchState.IN_GAME);

        // Set up the first round
        gameSession.getCurrentMatch().advanceRound();

        // Create a full context round update message to send to proctor
        RoundUpdate fullContextRoundUpdate = RoundUpdate
                .builder()
                .round(gameSession.getCurrentRound())
                .recipient(gameSession.getProctor().getPlayerId())
                .build();

        // Create sanitized round update for other players
        RoundUpdate limitedContextRoundUpdate = RoundUpdate.builder()
                .round(sanitizeRound(gameSession.getCurrentRound()))
                .recipients(gameSession.getPlayerList().stream()
                        .map(Player::getPlayerId)
                        .filter(playerId -> !playerId.equals(gameSession.getProctor().getPlayerId()))
                        .collect(Collectors.toList()))
                .build();


        // Send multi-message back to processor
        return SockbowlMultiOutMessage
                .builder()
                .sockbowlOutMessage(new GameStartedMessage())
                .sockbowlOutMessage(fullContextRoundUpdate)
                .sockbowlOutMessage(limitedContextRoundUpdate)
                .build();
    }

}
