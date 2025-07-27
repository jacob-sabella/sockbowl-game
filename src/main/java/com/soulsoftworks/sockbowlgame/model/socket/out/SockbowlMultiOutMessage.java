package com.soulsoftworks.sockbowlgame.model.socket.out;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Singular;
import lombok.experimental.SuperBuilder;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class SockbowlMultiOutMessage extends SockbowlOutMessage {

    @Singular("sockbowlOutMessage")
    private List<SockbowlOutMessage> sockbowlOutMessages;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.MULTI;
    }
}
