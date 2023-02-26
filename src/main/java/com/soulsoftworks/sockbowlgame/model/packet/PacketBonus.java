package com.soulsoftworks.sockbowlgame.model.packet;

import lombok.Data;

@Data
public class PacketBonus {
    private int packetId;
    private int bonusId;
    private int number;
    private Packet packet;
    private Bonus bonus;
}
