package com.soulsoftworks.sockbowlgame.model.socket.out.game;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

/**
 * Broadcast every second while an AUTO_PROCTOR round's question is being revealed.
 * Carries only the cumulative revealed text — never the unrevealed remainder.
 */
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class ReadingUpdate extends SockbowlOutMessage {

    /** Cumulative revealed text so far (HTML tags stripped, plain text). */
    private String revealedText;

    /** Number of words revealed so far. */
    private int revealedWordCount;

    /** Total words in the tossup question. */
    private int totalWordCount;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.GAME;
    }
}
