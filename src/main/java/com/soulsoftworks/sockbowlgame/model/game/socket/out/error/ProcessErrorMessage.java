package com.soulsoftworks.sockbowlgame.model.game.socket.out.error;

import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class ProcessErrorMessage extends SockbowlOutMessage {

    private String error;
    public ProcessErrorMessage(String error) {
        this.error = error;
    }

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.ERROR;
    }

    public static ProcessErrorMessage accessDeniedMessage(SockbowlInMessage sockbowlInMessage){
        return ProcessErrorMessage
                .builder()
                .error(sockbowlInMessage.getClass().getSimpleName() + ": Permission Denied")
                .recipient(sockbowlInMessage.getOriginatingPlayerId()).build();
    }

}