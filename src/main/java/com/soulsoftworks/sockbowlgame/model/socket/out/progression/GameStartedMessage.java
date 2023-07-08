package com.soulsoftworks.sockbowlgame.model.socket.out.progression;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;

public class GameStartedMessage extends SockbowlOutMessage {
    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.PROGRESSION;
    }
}
