package com.soulsoftworks.sockbowlgame.model.packet;

import lombok.Data;

@Data
public class EventPacket {
    private int eventId;
    private int packetId;
    private int round;
    private Event event;
    private Packet packet;
}
