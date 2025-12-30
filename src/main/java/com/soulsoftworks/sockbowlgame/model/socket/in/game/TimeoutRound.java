package com.soulsoftworks.sockbowlgame.model.socket.in.game;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
@NoArgsConstructor
public class TimeoutRound extends SockbowlInMessage {

    /**
     * Flag indicating whether this timeout was triggered automatically by the server timer.
     * True = auto-triggered by GameTimerService, False = manually triggered by proctor.
     */
    @Builder.Default
    private boolean isAutoTimeout = false;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.GAME;
    }
}
