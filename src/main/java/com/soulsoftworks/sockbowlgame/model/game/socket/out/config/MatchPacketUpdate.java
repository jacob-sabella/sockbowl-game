package com.soulsoftworks.sockbowlgame.model.game.socket.out.config;

import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class MatchPacketUpdate extends SockbowlOutMessage {

    private long packetId;
    private String packetName;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.CONFIG;
    }
}
