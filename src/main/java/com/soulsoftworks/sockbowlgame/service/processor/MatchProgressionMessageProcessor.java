package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.game.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.progression.StartMatchMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.error.ProcessErrorMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.progression.GameStartedMessage;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.MatchState;

public class MatchProgressionMessageProcessor extends GameMessageProcessor  {

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(StartMatchMessage.class, this::startMatch);
    }

    public SockbowlOutMessage startMatch(SockbowlInMessage startMatchMessage){

        GameSession gameSession = startMatchMessage.getGameSession();

        // Check if the player making the request is the game owner
        if (!gameSession.isPlayerGameOwner(startMatchMessage.getOriginatingPlayerId())) {
            // If not, return an access denied error message
            return ProcessErrorMessage.accessDeniedMessage(startMatchMessage);
        }

        // Change the match state to in game
        gameSession.getCurrentMatch().setMatchState(MatchState.IN_GAME);

        // Send notification that game started
        return new GameStartedMessage();
    }

}
