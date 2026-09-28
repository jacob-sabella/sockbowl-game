package com.soulsoftworks.sockbowlgame.model.socket.out.config;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class MatchPacketUpdate extends SockbowlOutMessage {

    /**
     * The packet's id. Sent only to the player who loaded it (the proctor, or
     * the owner in a proctorless mode); null for every other recipient
     * (R3-G-01): a player who knows the id could load the packet as proctor
     * of another game and read its answers.
     */
    private String packetId;
    private String packetName;
    /** Number of tossups in the packet, so clients can show "Tossup N of M" progress. */
    private int tossupCount;
    /**
     * Number of playable bonuses (0-part bonuses dropped), so the UI can show "no bonuses"
     * and non-proctors need not ask sockbowl-questions.
     */
    private int bonusCount;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.CONFIG;
    }
}
