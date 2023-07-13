package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.packet.PacketTossup;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.LimitedContextTossupUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.FullContextTossupUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameStartedMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.MatchState;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

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

        // Change the match state to in game
        gameSession.getCurrentMatch().setMatchState(MatchState.IN_GAME);

        // Get first tossup of the round
        PacketTossup packetTossup = gameSession.getCurrentMatch().getPacket().getTossups().get(0);

        // Set up the first round
        gameSession.getCurrentMatch().getCurrentRound().setupRound(1, packetTossup.getTossup().getQuestion(),
                packetTossup.getTossup().getAnswer());

        // Create a full context question message to send to proctor
        FullContextTossupUpdate fullContextTossupUpdate = FullContextTossupUpdate
                .builder()
                .packetTossup(packetTossup)
                .recipient(gameSession.getProctor().getPlayerId())
                .build();

        // Create limited context message for other players
        LimitedContextTossupUpdate limitedContextTossupUpdate = LimitedContextTossupUpdate.builder()
                .tossupNumber(1)
                .recipients(gameSession.getPlayerList().stream()
                        .map(Player::getPlayerId)
                        .filter(playerId -> !playerId.equals(gameSession.getProctor().getPlayerId()))
                        .collect(Collectors.toList()))
                .build();


        // Send multi-message back to processor
        return SockbowlMultiOutMessage
                .builder()
                .sockbowlOutMessage(new GameStartedMessage())
                .sockbowlOutMessage(fullContextTossupUpdate)
                .sockbowlOutMessage(limitedContextTossupUpdate)
                .build();
    }

}
