package com.soulsoftworks.sockbowlgame.model.socket.in.game;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Single-player action: the lone player types an answer to the current tossup.
 * Combines buzz + answer in one message — the automated judge adjudicates it,
 * standing in for the human proctor's Right/Wrong.
 */
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
@NoArgsConstructor
public class SubmitAnswer extends SockbowlInMessage {
    private String answerText;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.GAME;
    }
}
