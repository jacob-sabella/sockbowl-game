package com.soulsoftworks.sockbowlgame.model.socket.out.config;


import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class GameSessionUpdate extends SockbowlOutMessage {

    GameSession gameSession;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.CONFIG;
    }
}
