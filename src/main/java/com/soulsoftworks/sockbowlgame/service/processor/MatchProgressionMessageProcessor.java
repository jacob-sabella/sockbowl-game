package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.FullContextTossupUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameStartedMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.MatchState;

public class MatchProgressionMessageProcessor extends MessageProcessor {

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(StartMatch.class, this::startMatch);
    }

    public SockbowlOutMessage startMatch(SockbowlInMessage startMatchMessage){

        GameSession gameSession = startMatchMessage.getGameSession();

        // Check if the player making the request is the game owner
        if (!gameSession.isPlayerGameOwner(startMatchMessage.getOriginatingPlayerId())) {
            // If not, return an access denied error message
            return ProcessError.accessDeniedMessage(startMatchMessage);
        }

        // Change the match state to in game
        gameSession.getCurrentMatch().setMatchState(MatchState.IN_GAME);

        // Create a full context question message to send to proctor
        FullContextTossupUpdate fullContextTossupUpdate = FullContextTossupUpdate
                .builder()
                .tossup(gameSession.getCurrentMatch().getPacket().getTossups().get(0).getTossup())
                .recipient(gameSession.getProctor().getPlayerId())
                .build();

        // Create a multi-message
        SockbowlMultiOutMessage sockbowlMultiOutMessage = SockbowlMultiOutMessage
                .builder()
                .sockbowlOutMessage(new GameStartedMessage())
                .build();

        // Send multi-message back to processor
        return sockbowlMultiOutMessage;
    }

}
