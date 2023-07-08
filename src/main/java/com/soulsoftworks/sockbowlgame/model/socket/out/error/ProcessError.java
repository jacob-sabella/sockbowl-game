package com.soulsoftworks.sockbowlgame.model.socket.out.error;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class ProcessError extends SockbowlOutMessage {

    private String error;
    public ProcessError(String error) {
        this.error = error;
    }

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.ERROR;
    }

    public static ProcessError accessDeniedMessage(SockbowlInMessage sockbowlInMessage){
        return ProcessError
                .builder()
                .error(sockbowlInMessage.getClass().getSimpleName() + ": Permission Denied")
                .recipient(sockbowlInMessage.getOriginatingPlayerId()).build();
    }


    public static ProcessError wrongStateMessage(SockbowlInMessage sockbowlInMessage){
        return ProcessError
                .builder()
                .error(sockbowlInMessage.getClass().getSimpleName() + ": Not executable for state " +
                        sockbowlInMessage.getGameSession().getCurrentMatch().getMatchState())
                .recipient(sockbowlInMessage.getOriginatingPlayerId()).build();
    }

}