package com.soulsoftworks.sockbowlgame.model.game.socket.in;

import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;


@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class TestSockbowlInMessage extends SockbowlInMessage {
    String testString = "TEST";

    public TestSockbowlInMessage(String originatingPlayerId, String gameSessionId) {
        super(originatingPlayerId, gameSessionId);
    }

    @Override
    public String getMessageType() {
        return "GENERIC";
    }
}
