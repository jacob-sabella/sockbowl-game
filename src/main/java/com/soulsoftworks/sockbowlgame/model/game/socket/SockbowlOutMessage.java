package com.soulsoftworks.sockbowlgame.model.game.socket;

import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import lombok.Data;

@Data
public abstract class SockbowlOutMessage {

    public SockbowlOutMessage() {
    }

    public abstract MessageTypes getMessageType();
}
