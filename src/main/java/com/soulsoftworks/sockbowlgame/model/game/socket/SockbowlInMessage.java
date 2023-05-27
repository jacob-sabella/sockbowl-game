package com.soulsoftworks.sockbowlgame.model.game.socket;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
public abstract class SockbowlInMessage {

    private String originatingPlayerId;
    private String gameSessionId;

    public SockbowlInMessage(String originatingPlayerId, String gameSessionId) {
        this.originatingPlayerId = originatingPlayerId;
        this.gameSessionId = gameSessionId;
    }

    public abstract String getMessageType();
}
