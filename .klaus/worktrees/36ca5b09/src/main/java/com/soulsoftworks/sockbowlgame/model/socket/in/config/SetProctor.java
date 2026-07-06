package com.soulsoftworks.sockbowlgame.model.socket.in.config;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes.CONFIG;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
@NoArgsConstructor
public class SetProctor extends SockbowlInMessage {

    private String targetPlayer;

    @Override
    public MessageTypes getMessageType() {
        return CONFIG;
    }
}
