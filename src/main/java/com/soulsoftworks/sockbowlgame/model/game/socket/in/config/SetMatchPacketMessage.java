package com.soulsoftworks.sockbowlgame.model.game.socket.in.config;

import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.SockbowlInMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
@NoArgsConstructor
public class SetMatchPacketMessage extends SockbowlInMessage {

    long packetId;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.CONFIG;
    }
}
