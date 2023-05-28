package com.soulsoftworks.sockbowlgame.model.game.socket.in;

import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;



@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class TestSockbowlInMessage extends SockbowlInMessage {
    String testString = "TEST";

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.GENERIC;
    }
}
