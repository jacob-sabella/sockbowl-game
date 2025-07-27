package com.soulsoftworks.sockbowlgame.model.packet.relationships;

import com.soulsoftworks.sockbowlgame.model.packet.nodes.Packet;
import lombok.Data;

@Data
public class UsesPacketAtRound {

    private Long id;
    private final Integer round;
    private final Packet packet;

}
