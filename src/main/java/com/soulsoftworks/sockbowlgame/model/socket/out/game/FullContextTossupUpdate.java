package com.soulsoftworks.sockbowlgame.model.socket.out.game;

import com.soulsoftworks.sockbowlgame.model.packet.PacketTossup;
import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class FullContextTossupUpdate extends SockbowlOutMessage {

    private PacketTossup packetTossup;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.GAME;
    }
}
