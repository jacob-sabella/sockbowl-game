package com.soulsoftworks.sockbowlgame.model.packet;

import lombok.Data;

@Data
public class PacketTossup {
    private int packetId;
    private int tossupId;
    private int number;
    private Packet packet;
    private Tossup tossup;
}
