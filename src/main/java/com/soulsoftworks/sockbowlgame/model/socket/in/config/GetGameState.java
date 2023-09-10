package com.soulsoftworks.sockbowlgame.model.socket.in.config;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
@NoArgsConstructor
public class GetGameState extends SockbowlInMessage {
    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.CONFIG;
    }
}
