package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.game.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.progression.StartMatchMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;

public class MatchProgressionMessageProcessor extends GameMessageProcessor  {

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(StartMatchMessage.class, this::startMatch);
    }


    public SockbowlOutMessage startMatch(SockbowlInMessage startMatchMessage){
        return null;
    }

}
