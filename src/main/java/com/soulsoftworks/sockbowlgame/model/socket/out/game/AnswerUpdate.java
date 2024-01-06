package com.soulsoftworks.sockbowlgame.model.socket.out.game;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.state.Round;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class AnswerUpdate extends SockbowlOutMessage {

    Round currentRound;
    boolean correct;
    String playerId;
    private List<Round> previousRounds;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.GAME;
    }
}
