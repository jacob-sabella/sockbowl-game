package com.soulsoftworks.sockbowlgame.model.game.socket.out.progression;

import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;

public class GameStartedMessage extends SockbowlOutMessage {
    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.PROGRESSION;
    }
}
