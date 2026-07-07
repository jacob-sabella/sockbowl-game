package com.soulsoftworks.sockbowlgame.model.socket.out.config;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class MatchPacketUpdate extends SockbowlOutMessage {

    private String packetId;
    private String packetName;
    /** Number of tossups in the packet, so clients can show "Tossup N of M" progress. */
    private int tossupCount;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.CONFIG;
    }
}
