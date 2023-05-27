package com.soulsoftworks.sockbowlgame.model.game.socket;

import lombok.Data;

@Data
public abstract class SockbowlOutMessage {

    public SockbowlOutMessage() {
    }

    public abstract String getMessageType();
}
