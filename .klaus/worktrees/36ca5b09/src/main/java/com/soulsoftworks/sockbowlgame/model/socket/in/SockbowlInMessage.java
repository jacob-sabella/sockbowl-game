package com.soulsoftworks.sockbowlgame.model.socket.in;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
public abstract class SockbowlInMessage {

    private String originatingPlayerId;
    private String gameSessionId;
    private GameSession gameSession;

    public abstract MessageTypes getMessageType();
}
