package com.soulsoftworks.sockbowlgame.model.socket.out.game;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

/**
 * Message sent to clients to update timer countdown.
 * Broadcast every second while a timer is active.
 */
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class TimerUpdate extends SockbowlOutMessage {

    /**
     * Type of timer being updated: "TOSSUP" or "BONUS"
     */
    private String timerType;

    /**
     * Remaining time in seconds
     */
    private int remainingSeconds;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.GAME;
    }

}
